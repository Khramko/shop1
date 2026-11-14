# 📊 Мониторинг

Все 4 сервиса экспортируют метрики в формате **Prometheus** через `/actuator/prometheus`. Логи — **JSON** (`logstash-logback-encoder`) с MDC-полями. В k8s опционально ставится `kube-prometheus-stack` (Prometheus + Grafana + Alertmanager). Отдельного `ServiceMonitor` в манифестах нет — предполагается либо статический scrape-конфиг, либо `additionalScrapeConfigs` в values Prometheus (см. ниже).

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

---

## Установка Prometheus + Grafana (k8s)

```bash
helm repo add prometheus-community https://prometheus-community.github.io/helm-charts
helm repo update

helm install prometheus prometheus-community/kube-prometheus-stack \
  -n monitoring \
  --create-namespace
```

По умолчанию `kube-prometheus-stack` собирает метрики подов с аннотацией `prometheus.io/scrape: "true"`. В текущих `deployment.yaml` такой аннотации нет, поэтому нужен либо `additionalScrapeConfigs`, либо `PodMonitor`. Минимальный `additionalScrapeConfigs`:

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
            regex: (user-service|billing-service|order-service|notification-service)
            action: keep
          - source_labels: [__meta_kubernetes_pod_ip]
            target_label: __address__
            replacement: $1:8081
          - source_labels: [__meta_kubernetes_pod_label_app]
            target_label: application
```

> Порт разный у сервисов (8081–8084), поэтому либо 4 отдельных `job_name`, либо `targetPort` из `Service`. Проще — 4 `job_name` с явным `static_configs` на `shop-<service>.shop.svc.cluster.local:<port>`.

Переустановка:

```bash
helm upgrade prometheus prometheus-community/kube-prometheus-stack \
  -n monitoring -f prometheus-values.yaml
```

### Проверка таргетов

```bash
kubectl port-forward -n monitoring svc/prometheus-kube-prometheus-prometheus 9090:9090
```

Открыть `http://localhost:9090/targets` — все 4 сервиса в статусе **UP**.

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

### Kafka consumer lag

```promql
sum by (group, topic) (kafka_consumer_fetch_manager_records_lag)
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
```

---

## Нагрузочное тестирование

```bash
sudo snap install hey
hey -z 5m -c 5 -host "arch.homework" http://localhost:8080/health
```

Во время прогона смотреть в Grafana:

- `http_server_requests_seconds` p95/p99 — не деградирует ли;
- `hikaricp_connections_active` — не упирается ли в пул (по умолчанию 10);
- `jvm_gc_pause_seconds` — не растут ли паузы;
- `kafka_consumer_fetch_manager_records_lag` — успевают ли consumer'ы.

Для сквозной нагрузки (регистрация → заказ) используйте Postman/Newman с `-n 100` (Newman не умеет в открытую модель, но `-n 100` прогонит 100 итераций коллекции — это 100 регистраций + 100 deposit + 200 заказов).

---

## Что НЕ покрыто

- **Distributed tracing** (OpenTelemetry / Jaeger) — не подключён. Склейка событий одного заказа между сервисами возможна только по `orderId` в логах и по `request_id` в рамках одного HTTP-запроса.
- **Централизованные логи** (Loki / ELK) — не развёрнуты. JSON-логи пишутся в stdout, доступны через `kubectl logs`.
- **`ServiceMonitor`** — в манифестах отсутствует; scrape настраивается через `additionalScrapeConfigs` (см. выше).
- **Grafana-дашборды** — JSON-дашборд не приложен к репозиторию. Панели из «Полезные PromQL» собираются вручную.
- **Бизнес-метрики** — нет счётчиков `orders_created_total`, `orders_paid_total`, `orders_failed_total`, `funds_withdrawn_total`. Сейчас бизнес-состояние видно только через `http_server_requests_seconds_count{uri="/orders"}` и через логи. Для продакшена стоит добавить `MeterRegistry.counter("orders.paid")` в `OrderEventPublisher` / listener'ы.

Эти пробелы отражены в [`ARCHITECTURE.md`](../ARCHITECTURE.md) → «Известные ограничения».