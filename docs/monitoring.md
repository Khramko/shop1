# 📊 Мониторинг

Все **6 сервисов** экспортируют метрики в формате **Prometheus** через `/actuator/prometheus`. Логи — **JSON** (`logstash-logback-encoder`) с MDC-полями. В k8s опционально ставится `kube-prometheus-stack` (Prometheus + Grafana + Alertmanager). Отдельного `ServiceMonitor` в манифестах нет — предполагается либо статический scrape-конфиг, либо `additionalScrapeConfigs` в values Prometheus (см. ниже).

---

## Что уже включено в сервисах

### Actuator

В каждом `application.yaml`:

```yaml
management:
  endpoints.web.exposure.include: health,info,prometheus
  endpoint.health.show-details: always
  metrics:
    tags.application: ${spring.application.name}
    distribution:
      percentiles-histogram.http.server.requests: true
      percentiles.http.server.requests: 0.5,0.95,0.99
```

- `/actuator/health` — `permitAll` в `SecurityConfig`, используется в readiness/liveness.
- `/actuator/prometheus` — `permitAll`, отдаёт метрики Micrometer.
- Тег `application=<service-name>` — единый label для всех метрик сервиса.
- **Гистограммы** по `http.server.requests` — позволяют считать `histogram_quantile(0.95, ...)`.
- **Перцентили** 0.5 / 0.95 / 0.99 — доступны как `http_server_requests_seconds{quantile="0.95"}` без отдельного bucket-агрегата.

### JSON-логи

`common-logging/logback-spring.xml`:

```xml
<appender name="JSON" class="ch.qos.logback.core.ConsoleAppender">
    <encoder class="net.logstash.logback.encoder.LogstashEncoder">
        <includeMdc>true</includeMdc>
        <customFields>{"service":"${APP_NAME}"}</customFields>
        <fieldNames>
            <timestamp>@timestamp</timestamp>
            <version>[ignore]</version>
            <levelValue>[ignore]</levelValue>
            <thread>[ignore]</thread>
            <logger>[ignore]</logger>
        </fieldNames>
    </encoder>
</appender>
```

`RequestLoggingFilter` кладёт в MDC на каждый запрос:

| Поле MDC | Значение |
|---|---|
| `request_id` | UUID, генерируется на входе |
| `http_method` | `GET` / `POST` / ... |
| `http_path` | URI |
| `remote_ip` | `X-Forwarded-For[0]` или `getRemoteAddr()` |
| `http_status` | статус ответа |
| `duration_ms` | время обработки |

Итоговый JSON-лог:

```json
{
  "@timestamp": "2026-11-14T17:01:26.322Z",
  "message": "Request completed",
  "level": "INFO",
  "service": "order-service",
  "request_id": "a1b2c3d4-...",
  "http_method": "POST",
  "http_path": "/orders",
  "remote_ip": "10.244.0.5",
  "http_status": "202",
  "duration_ms": "26"
}
```

Это позволяет фильтровать в Loki/ELK по `service`, `http_status`, `request_id` (склейка запросов одного пользователя).

### Saga-логи

`SagaOrchestrator` пишет в лог каждый переход состояния:

```json
{
  "@timestamp": "2026-11-14T17:01:26.322Z",
  "level": "INFO",
  "service": "order-service",
  "message": "Saga[42] funds reserved → reserve stock",
  "order_id": 42,
  "step": "FUNDS_RESERVED"
}
```

`StockEventsListener` / `CourierEventsListener` / `SagaFundsListener` — аналогично:

```json
{ "message": "Reserved order=42 sku=SKU-001 qty=2 left=98", "order_id": 42 }
{ "message": "Reserved courier=1 slot=2026-11-16T10:00 order=42", "order_id": 42 }
{ "message": "Reserved funds order=42 amount=300 balance=700", "order_id": 42 }
```

По `order_id` можно склеить всю историю саги в Loki/ELK (если они развёрнуты).

---

## Метрики

### HTTP

| Метрика | Тип | Описание |
|---|---|---|
| `http_server_requests_seconds_count` | counter | Число запросов, метки `application`, `method`, `uri`, `status`, `outcome` |
| `http_server_requests_seconds_sum` | counter | Суммарное время |
| `http_server_requests_seconds_bucket` | histogram | Бакеты для `histogram_quantile` |
| `http_server_requests_seconds{quantile="0.5/0.95/0.99"}` | summary | Перцентили (pre-computed) |
| `http_server_requests_seconds_max` | gauge | Максимум |

### JVM

`jvm_memory_used_bytes`, `jvm_gc_pause_seconds_*`, `jvm_threads_live_threads`, `jvm_classes_loaded_classes` — стандартные Micrometer.

### HikariCP

`hikaricp_connections_active`, `hikaricp_connections_idle`, `hikaricp_connections_pending`, `hikaricp_connections_timeout_total` — состояние пула к БД.

### Kafka

`kafka_consumer_fetch_manager_records_lag`, `kafka_consumer_fetch_manager_records_consumed_total`, `kafka_producer_record_send_total`, `kafka_producer_record_error_total` — лаг consumer'ов и ошибки producer'а (Spring Kafka через Micrometer).

### Actuator

`process_cpu_usage`, `process_uptime_seconds`, `system_cpu_usage`, `system_load_average_1m`.

### Saga-метрики (не реализованы, но нужны)

Сейчас бизнес-состояние саги видно только через логи и SQL к `saga_states`. Для продакшена стоит добавить:

| Метрика | Где инкрементить | Зачем |
|---|---|---|
| `orders_created_total` | `OrderService.createSaga` | RPS создания заказов |
| `orders_paid_total` | `SagaOrchestrator.onCourierReserved` | успешные заказы |
| `orders_failed_total{reason}` | `SagaOrchestrator.failSaga`, `compensate` | провалы по причинам (`FUNDS`, `STOCK`, `COURIER`) |
| `orders_compensated_total{step}` | `SagaOrchestrator.compensate` | сколько откатов по шагам |
| `saga_stuck_total{step}` | планировщик таймаута (не реализован) | саги, «зависшие» в `STARTED`/`FUNDS_RESERVED`/`STOCK_RESERVED` |
| `funds_reserved_total` / `funds_released_total` | `SagaFundsListener` | объём резервов/возвратов |
| `stock_reserved_total` / `stock_released_total` | `StockEventsListener` | объём резервов/возвратов |
| `courier_reserved_total` / `courier_released_total` | `CourierEventsListener` | объём резервов/возвратов |

Пример добавления:

```java
// SagaOrchestrator
private final MeterRegistry meter;

public void onCourierReserved(CourierReservedEvent e) {
    // ...
    meter.counter("orders.paid").increment();
}
```

Это позволит в Grafana строить:

```promql
# RPS успешных заказов
sum(rate(orders_paid_total[5m]))

# Доля провалов
sum(rate(orders_failed_total[5m])) / sum(rate(orders_created_total[5m]))

# Застрявшие саги
sum by (step) (saga_stuck_total)
```

См. [`docs/limitations.md`](limitations.md) → п. 9.

---

## Установка Prometheus + Grafana (k8s)

```bash
helm repo add prometheus-community https://prometheus-community.github.io/helm-charts
helm repo update

helm install prometheus prometheus-community/kube-prometheus-stack \
  -n monitoring \
  --create-namespace
```

По умолчанию `kube-prometheus-stack` собирает метрики подов с аннотацией `prometheus.io/scrape: "true"`. В текущих `deployment.yaml` такой аннотации нет, поэтому нужен либо `additionalScrapeConfigs`, либо `PodMonitor`. Минимальный `additionalScrapeConfigs` для 6 сервисов:

```yaml
# prometheus-values.yaml
prometheus:
  prometheusSpec:
    additionalScrapeConfigs:
      - job_name: shop-services
        metrics_path: /actuator/prometheus
        kubernetes_sd_configs:
          - role: pod
            namespaces:
              names: [shop]
        relabel_configs:
          - source_labels: [__meta_kubernetes_pod_label_app]
            regex: (user-service|billing-service|order-service|notification-service|warehouse-service|delivery-service)
            action: keep
          - source_labels: [__meta_kubernetes_pod_label_app]
            target_label: application
```

> Порт разный у сервисов (8081–8086), поэтому либо 6 отдельных `job_name`, либо `targetPort` из `Service`. Проще — 6 `job_name` с явным `static_configs` на `shop-<service>.shop.svc.cluster.local:<port>`:

```yaml
    additionalScrapeConfigs:
      - job_name: shop-user-service
        static_configs:
          - targets: ['user-service.shop.svc.cluster.local:8081']
      - job_name: shop-billing-service
        static_configs:
          - targets: ['billing-service.shop.svc.cluster.local:8082']
      - job_name: shop-order-service
        static_configs:
          - targets: ['order-service.shop.svc.cluster.local:8083']
      - job_name: shop-notification-service
        static_configs:
          - targets: ['notification-service.shop.svc.cluster.local:8084']
      - job_name: shop-warehouse-service
        static_configs:
          - targets: ['warehouse-service.shop.svc.cluster.local:8085']
      - job_name: shop-delivery-service
        static_configs:
          - targets: ['delivery-service.shop.svc.cluster.local:8086']
```

Переустановка:

```bash
helm upgrade prometheus prometheus-community/kube-prometheus-stack \
  -n monitoring -f prometheus-values.yaml
```

### Проверка таргетов

```bash
kubectl port-forward -n monitoring svc/prometheus-kube-prometheus-prometheus 9090:9090
```

Открыть `http://localhost:9090/targets` — все 6 сервисов в статусе **UP**.

### Grafana

```bash
kubectl port-forward -n monitoring svc/prometheus-grafana 3000:80
kubectl get secret prometheus-grafana -n monitoring \
  -o jsonpath="{.data.admin-password}" | base64 --decode ; echo
```

Открыть `http://localhost:3000`, логин `admin`.

---

## Полезные PromQL

### Latency p95 по эндпоинтам

```promql
histogram_quantile(
  0.95,
  sum by (application, uri, le) (
    rate(http_server_requests_seconds_bucket[5m])
  )
)
```

### RPS по эндпоинтам

```promql
sum by (application, uri) (
  rate(http_server_requests_seconds_count[1m])
)
```

### Error rate (5xx)

```promql
sum by (application) (
  rate(http_server_requests_seconds_count{status=~"5.."}[5m])
)
/
sum by (application) (
  rate(http_server_requests_seconds_count[5m])
)
```

### CPU и память подов

```promql
sum by (pod) (rate(container_cpu_usage_seconds_total{namespace="shop", container!=""}[5m]))
sum by (pod) (container_memory_working_set_bytes{namespace="shop", container!=""})
```

### Kafka consumer lag по группам

```promql
sum by (group, topic) (kafka_consumer_fetch_manager_records_lag)
```

Особенно важно для `order-service-saga` — там 12 топиков:

```promql
sum by (topic) (
  kafka_consumer_fetch_manager_records_lag{group="order-service-saga"}
)
```

Если `lag` растёт и не уменьшается — consumer завис или упал.

### HikariCP: насыщение пула

```promql
hikaricp_connections_active / hikaricp_connections_max
```

При `> 0.8` — риск `SQLTransientConnectionException` под нагрузкой.

### JVM GC pause

```promql
rate(jvm_gc_pause_seconds_sum[5m]) / rate(jvm_gc_pause_seconds_count[5m])
```

---

## Алертинг

### В Grafana

**Alerting → Alert rules → New alert rule**.

**Error rate > 5/мин:**

```promql
sum(rate(http_server_requests_seconds_count{application="order-service", status=~"5.."}[5m]))
```
Условие `IS ABOVE 5`, Evaluation `1m`, Pending `5m`.

**Latency p95 > 1s:**

```promql
histogram_quantile(0.95,
  sum by (le) (rate(http_server_requests_seconds_bucket{application="order-service"}[5m]))
)
```
Условие `IS ABOVE 1`, Evaluation `1m`, Pending `5m`.

**Kafka lag > 1000:**

```promql
sum by (group, topic) (kafka_consumer_fetch_manager_records_lag)
```
Условие `IS ABOVE 1000`, Evaluation `1m`, Pending `5m`.

**Saga stuck (если метрика добавлена):**

```promql
sum by (step) (saga_stuck_total) > 10
```
Условие `IS ABOVE 10`, Evaluation `1m`, Pending `5m`.

### Через PrometheusRule (k8s)

```yaml
apiVersion: monitoring.coreos.com/v1
kind: PrometheusRule
metadata:
  name: shop-alerts
  namespace: monitoring
  labels:
    release: prometheus
spec:
  groups:
    - name: shop
      rules:
        - alert: ShopHighErrorRate
          expr: |
            sum by (application) (rate(http_server_requests_seconds_count{status=~"5.."}[5m]))
            /
            sum by (application) (rate(http_server_requests_seconds_count[5m]))
            > 0.05
          for: 5m
          labels: { severity: warning }
          annotations:
            summary: "{{ $labels.application }} error rate > 5%"
        - alert: ShopKafkaLag
          expr: sum by (group, topic) (kafka_consumer_fetch_manager_records_lag) > 1000
          for: 5m
          labels: { severity: warning }
          annotations:
            summary: "consumer {{ $labels.group }} lag on {{ $labels.topic }} > 1000"
        - alert: ShopSagaStuck
          expr: sum by (step) (saga_stuck_total) > 10
          for: 5m
          labels: { severity: warning }
          annotations:
            summary: "saga stuck in {{ $labels.step }} > 10"
```

> `ShopSagaStuck` работает только если реализован планировщик таймаута (см. `docs/limitations.md` → п. 3). Сейчас метрики нет.

---

## Нагрузочное тестирование

```bash
sudo snap install hey
hey -z 5m -c 5 -host "arch.homework" http://localhost:8080/health
```

Для сквозной нагрузки (регистрация → deposit → заказ) используйте Postman/Newman с `-n 100`:

```bash
npx newman run etc/shop-saga.postman_collection.json \
  --environment <tmp> -n 100 --delay-request 100
```

Это 100 регистраций + 100 deposit + 100+ заказов (happy + fail + idempotency). Смотреть в Grafana:

- `http_server_requests_seconds` p95/p99 — не деградирует ли;
- `hikaricp_connections_active` — не упирается ли в пул (по умолчанию 10);
- `jvm_gc_pause_seconds` — не растут ли паузы;
- `kafka_consumer_fetch_manager_records_lag{group="order-service-saga"}` — успевает ли оркестратор;
- `kafka_consumer_fetch_manager_records_lag{group="warehouse-service"}` / `delivery-service` — успевают ли участники.

---

## Проверка saga-состояния через БД

Метрик саги пока нет, поэтому — SQL:

```bash
# распределение заказов по шагам саги
kubectl exec -n shop postgres-0 -- \
  psql -U app_user -d order_db -c \
  "SELECT step, count(*) FROM saga_states GROUP BY step ORDER BY count(*) DESC;"

# заказы, «зависшие» в промежуточных шагах > 5 минут
kubectl exec -n shop postgres-0 -- \
  psql -U app_user -d order_db -c \
  "SELECT order_id, step, failure_reason, updated_at
   FROM saga_states
   WHERE step IN ('STARTED','FUNDS_RESERVED','STOCK_RESERVED')
     AND updated_at < now() - interval '5 minutes'
   ORDER BY updated_at;"

# состояние резервов в billing
kubectl exec -n shop postgres-0 -- \
  psql -U app_user -d billing_db -c \
  "SELECT count(*), sum(amount) FROM reserved_funds;"

# состояние склада
kubectl exec -n shop postgres-0 -- \
  psql -U app_user -d warehouse_db -c \
  "SELECT sku, qty_available, qty_reserved FROM stock ORDER BY sku;"

# состояние курьеров (сколько слотов RESERVED)
kubectl exec -n shop postgres-0 -- \
  psql -U app_user -d delivery_db -c \
  "SELECT status, count(*) FROM courier_slots GROUP BY status;"
```

Эти запросы — замена отсутствующим бизнес-метрикам. В продакшене — вынести в экспортер (postgres_exporter + custom queries) и добавить в Grafana.

---

## Что НЕ покрыто

- **Distributed tracing** (OpenTelemetry / Jaeger) — не подключён. Склейка событий одного заказа между 6 сервисами возможна только по `orderId` в логах и по `request_id` в рамках одного HTTP-запроса.
- **Централизованные логи** (Loki / ELK) — не развёрнуты. JSON-логи пишутся в stdout, доступны через `kubectl logs`.
- **`ServiceMonitor`** — в манифестах отсутствует; scrape настраивается через `additionalScrapeConfigs` (см. выше).
- **Grafana-дашборды** — JSON-дашборд не приложен к репозиторию. Панели из «Полезные PromQL» собираются вручную.
- **Бизнес-метрики** — нет счётчиков `orders_paid_total`, `orders_failed_total`, `orders_compensated_total`, `saga_stuck_total`, `funds_reserved_total` и т.п. Сейчас бизнес-состояние видно только через `http_server_requests_seconds_count{uri="/orders"}` и через SQL к `saga_states`.
- **Postgres exporter** — нет `postgres_exporter`, метрики БД (connections, locks, replication lag) недоступны.

Эти пробелы отражены в [`docs/limitations.md`](limitations.md) → п. 7–9, 17.