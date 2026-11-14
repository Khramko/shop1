# ⚠️ Ограничения

Проект реализует Event Collaboration в объёме, достаточном для ДЗ («сервисы не падают»), но не является production-ready. Ниже — осознанно не реализованные вещи, их последствия и что нужно сделать для продакшена.

---

## 1. Нет Outbox pattern

**Что не так.** Публикация в Kafka и коммит транзакции БД — две независимые операции:

```java
@Transactional
public OrderResponse create(Long userId, BigDecimal price) {
    Order o = orderRepository.save(new Order(userId, price)); // ← коммит БД
    publisher.publishOrderCreated(o);                          // ← отдельный send()
    return OrderResponse.from(o);
}
```

Если процесс упадёт (OOM, kill -9, network partition до брокера) между `save()` и `kafka.send()`, событие **потеряется**, а заказ останется в `NEW` навсегда. Обратный случай — `send()` прошёл, а транзакция откатилась — consumer получит событие о несуществующем заказе (частично лечится идемпотентностью consumer'а, но `orderId` в БД не будет).

**Последствия.**
- Заказ «висит» в `NEW` при списанных/несписанных деньгах.
- UI polling бесконечно возвращает `NEW` (Newman падает через 20 попыток).

**Решение.** Transactional Outbox: таблица `outbox (id, aggregate_id, topic, payload, created_at, published_at NULL)`. В той же транзакции, что и `save(order)`, пишется строка `outbox`. Отдельный polling-воркер (`@Scheduled`) читает непубликованные строки, отправляет в Kafka, ставит `published_at`. At-least-once гарантия; consumer'ы уже идемпотентны.

**Стоимость.** ~1 таблица + 1 воркер на сервис. Не реализовано.

---

## 2. Нет retry / DLQ

**Что не так.** В `application.yaml` consumer'ов нет `DefaultErrorHandler`. Spring Kafka по умолчанию ретраит исключение **бесконечно** с backoff, блокируя партицию. Если consumer падает на «ядовитом» сообщении (например, `OrderCreatedEvent` без `orderId` или десериализация ломается), consumer застревает, лаг растёт, остальные сообщения в партиции не обрабатываются.

**Последствия.**
- Один битый payload → остановка обработки топика для группы.
- Нет способа «отложить» сообщение и продолжить.

**Решение.** `DefaultErrorHandler` с `FixedBackOff(1000L, 3)` + `DeadLetterPublishingRecoverer` в `<topic>.DLT`. DLT читается вручную или отдельным сервисом. Плюс метрика `kafka_consumer_fetch_manager_records_lag` и алерт.

**Стоимость.** ~20 строк конфигурации + DLT-топики. Не реализовано.

---

## 3. Нет saga-компенсации

**Что не так.** Сценарий успешного заказа — цепочка `order.created → funds.withdrawn → order.paid`. Если `funds.withdrawn` дошёл до order-service, но `order.paid` (публикуемый order-service'ом) потерян — notification-service не создаст HAPPY. Деньги списаны, заказ в `PAID`, но пользователь не уведомлён. Хуже: если order-service упал после `funds.withdrawn`, но до `orderRepository.save(PAID)`, заказ останется `NEW` при списанных деньгах.

**Последствия.**
- Деньги списаны, заказ `NEW` → деньги «зависли».
- HAPPY/ANGRY не создаются при потере `order.paid`/`order.failed`.

**Решение.** Saga с компенсацией:
- `billing-service` при получении `funds.withdrawn` (свой же publish) — нет, компенсация инициируется order-service'ом.
- Order-service: если `funds.withdrawn` получен, но `order.paid` не подтверждён через N секунд — publish `order.compensate` → billing возвращает деньги.
- Или: `billing-service` периодически сверяет `processed_orders` с фактическим состоянием заказов (через отдельный HTTP/топик) и откатывает «зависшие» списания.

**Стоимость.** Новая таблица `compensations`, таймеры, доп. топик. Не реализовано.

---

## 4. Kafka = single-node (KRaft)

**Что не так.** `k8s/kafka.yaml` и `docker-compose.yml` — один брокер, `replication.factor=1`, `min.insync.replicas=1`. При падении брокера:
- producer'ы получают `NotEnoughReplicas` / `TimeoutException`;
- consumer'ы теряют соединение;
- offsets-топик (`__consumer_offsets`) без реплик — потеря offset'ов при повреждении диска.

**Последствия.** Любой рестарт Kafka = простой всей системы. Нет отказоустойчивости.

**Решение.** Кластер ≥3 брокеров, `replication.factor≥3`, `min.insync.replicas≥2`, `unclean.leader.election.enable=false`. В k8s — StatefulSet с 3 репликами и anti-affinity. Или managed Kafka (Confluent Cloud, MSK, Yandex Managed Kafka).

**Стоимость.** Тройной ресурс, нетривиальный rollout. Не реализовано.

---

## 5. Postgres = single instance

**Что не так.** Один `StatefulSet` с `replicas: 1`, один PVC. Падение пода = простой записи до пересоздания (секунды-минуты). Повреждение PVC = потеря данных. Нет backup'ов.

**Последствия.** Downtime при рестарте; нет PITR; нет read-реплик.

**Решение.** Patroni / CloudNativePG / managed Postgres с репликацией и бэкапами (WAL-G, pgBackRest). `pg_dump` по cron — минимум.

**Стоимость.** Отдельная инфраструктурная задача. Не реализовано.

---

## 6. Eventual consistency

**Что не так.** `POST /orders` возвращает `202 Accepted` с `status: NEW`. Фактический `PAID`/`FAILED` появляется через Kafka (обычно 1–3 с, но без верхней границы). Клиент обязан опрашивать `GET /orders/{id}`.

**Последствия.**
- UI не может показать финальный статус сразу.
- Newman использует polling до 20 попыток × 500 мс = 10 с.
- При перегрузке/отставании consumer'ов задержка может быть больше.

**Решение.** WebSocket / SSE для push-уведомлений о смене статуса; либо client-side polling с экспоненциальным backoff и таймаутом. Сейчас — только polling.

**Стоимость.** Новый транспорт (STOMP/SSE) + фронт. Не реализовано.

---

## 7. Нет distributed tracing

**Что не так.** Один HTTP-запрос `POST /orders` порождает цепочку `order.created → funds.withdrawn → order.paid` в трёх сервисах. В логах есть `request_id` (per HTTP), но нет `trace_id`/`span_id`, сквозного через Kafka.

**Последствия.**
- Нельзя быстро ответить «почему заказ X остался NEW» — нужно грепать логи 3 сервисов по `orderId`.
- Нет latency-разбивки по hop'ам.

**Решение.** OpenTelemetry + `micrometer-tracing-bridge-otlp`, propagation через Kafka-заголовки (`traceparent`), экспорт в Jaeger/Tempo.

**Стоимость.** Зависимость + конфигурация на каждом сервисе + бэкенд. Не реализовано.

---

## 8. Нет централизованных логов

**Что не так.** JSON-логи пишутся в stdout, доступны через `kubectl logs` или `docker compose logs`. Нет агрегации и поиска.

**Последствия.**
- Поиск по `orderId`/`request_id` — вручную по каждому поду.
- Нет retention/rotation за пределами k8s (`containerLogMaxSize`).

**Решение.** Loki + Promtail (лёгкий вариант) или ELK/OpenSearch. `logback-spring.xml` уже совместим — JSON сразу парсится.

**Стоимость.** Отдельный стек. Не реализовано.

---

## 9. Нет бизнес-метрик

**Что не так.** Метрики только технические (`http.server.requests`, JVM, Hikari). Нет счётчиков:
- `orders_created_total{status="NEW"}`,
- `orders_paid_total`,
- `orders_failed_total{reason=...}`,
- `funds_withdrawn_total`,
- `notifications_created_total{type=...}`.

**Последствия.** В Grafana видно, что `/orders` вызывается, но не видно, сколько заказов успешны. Бизнес-здоровье оценивается только через логи.

**Решение.** `MeterRegistry.counter("orders.paid")` в listener'ах (`onFundsWithdrawn`, `onFundsWithdrawFailed`) и `saveHappy`/`saveAngry`. Плюс теги `reason`.

**Стоимость.** ~10 строк на сервис. Не реализовано.

---

## 10. Нет оптимистичной блокировки на `accounts`

**Что не так.** `Account` обновляется в `onOrderCreated`:

```java
Account acc = accountRepository.findByUserId(e.userId()).orElse(...);
acc.setBalance(acc.getBalance().subtract(e.price()));
accountRepository.save(acc);
```

Без `@Version` и без `SELECT ... FOR UPDATE`. Два `order.created` одного пользователя, обработанные параллельно (разные партиции — а ключ `userId`, значит одна партиция; но при `replicas>1` и ребалансе — возможно), могут дать **lost update**: оба прочитали `balance=700`, оба списали 300, итог `400` вместо `100`.

**Последствия.** В текущей конфигурации (`replicas: 1`, ключ `userId` → одна партиция → один consumer) гонки нет. Но при масштабировании — есть.

**Решение.** `@Version` на `Account` + `OptimisticLockingFailureException` → retry. Либо `@Lock(PESSIMISTIC_WRITE)` на `findByUserId`. Либо `UPDATE accounts SET balance = balance - :amount WHERE user_id = :uid AND balance >= :amount` с проверкой `rowCount`.

**Стоимость.** 1 аннотация + retry. Не реализовано (не требуется при `replicas: 1`).

---

## 11. Нет валидации на уровне consumer'ов

**Что не так.** `@KafkaListener` методы делают `if (e == null || e.orderId() == null) return;`, но не проверяют:
- `price > 0` (отрицательная цена → «пополнение» баланса);
- `amount == price` в `funds.withdrawn` (billing мог ошибиться);
- `accountId` принадлежит `userId`.

**Последствия.** Битое/вредоносное сообщение в топике (например, от скомпрометированного producer'а) может привести к некорректному балансу.

**Решение.** Валидация payload в listener'е + публикация в DLT при нарушении инварианта. Плюс JSON Schema / Avro + Schema Registry для контракта.

**Стоимость.** Схемы + валидаторы. Не реализовано.

---

## 12. Безопасность

**Что не так.**
- `JWT_SECRET_KEY` захардкожен в `k8s/common-secrets.yaml` (`prod-secret-key-change-it-now-32-chars-minimum`). Ротации нет.
- `DB_PASSWORD=securePassword123` — тоже в репозитории.
- Kafka без TLS/SASL: `PLAINTEXT://...` — любой под в namespace может читать/писать топики.
- `/actuator/prometheus` открыт без аутентификации — метрики доступны снаружи (через ingress? нет — ingress не маршрутизирует `/actuator`, но внутри кластера — да).
- Нет rate-limiting на `/auth/register` и `/auth/login` — brute-force пароля.
- Нет CSRF-защиты (не нужна при stateless JWT, но `csrf.disable()` — осознанно).

**Решение.**
- Секреты — через External Secrets Operator / Sealed Secrets / Vault, не в git.
- Kafka — SASL/SCRAM + TLS.
- Rate-limit — на ingress (`nginx.ingress.kubernetes.io/limit-rps`) или в приложении (Bucket4j).
- `/actuator/**` — `management.endpoints.web.exposure.include: health,info` в prod-профиле, `prometheus` — только для ServiceMonitor.

**Стоимость.** Инфраструктурная задача. Не реализовано (ДЗ не требует).

---

## 13. Единый `SecurityConfig` — нет ролей

**Что не так.** `common-security/SecurityConfig` даёт `authenticated()` всем, `permitAll` — только `/health`, `/actuator/**`, `/auth/**`. Ролей нет. `GET /api/profile/{id}` в legacy-коллекции был закрыт `@PreAuthorize("#id == principal.id")`, но в текущем `ProfileController` остался только `/api/profile` (без `{id}`), так что проверка не нужна.

**Последствия.** Нет RBAC. Все аутентифицированные пользователи равны. Для магазина это ок (у каждого свой аккаунт), но нет админ-функций.

**Решение.** Добавить `ROLE_ADMIN`, `@PreAuthorize("hasRole('ADMIN')")` на админ-эндпоинты, claim `roles` в JWT.

**Стоимость.** Расширение модели. Не требуется ДЗ.

---

## 14. Нет тестов

**Что не так.** В репозитории нет:
- юнит-тестов (`@SpringBootTest`, `@WebMvcTest`, `@DataJpaTest`);
- integration-тестов с Testcontainers (PostgreSQL + Kafka);
- contract-тестов на события (`UserCreatedEvent` и т.п.).

Покрытие — только E2E через Newman (11 запросов, 22 ассерта).

**Последствия.**
- Рефакторинг listener'ов/статус-машины не защищён.
- Нет теста на идемпотентность (`processed_orders`, повторный `funds.withdrawn`).
- Нет теста на гонку `accounts` (см. п. 10).

**Решение.** Testcontainers + `@SpringBootTest` для listener'ов; WireMock/Kafka testcontainers для producer'ов; `@DataJpaTest` для репозиториев.

**Стоимость.** Существенная. Не реализовано.

---

## 15. Прочее

- **`auto.create.topics.enable=true`.** Топики создаются с дефолтными `partitions=1`, `retention.ms=7d`. Для продакшена — явное создание с нужными параметрами.
- **`ddl-auto: validate`.** Схема управляется только Liquibase. Изменения в сущностях без миграции → падение на старте (это фича, но требует дисциплины).
- **`Schema Registry` нет.** Эволюция событий (добавление поля) — только через `spring.json.value.default.type` и обратную совместимость. Ломающие изменения не отслеживаются.
- **`release.sh` — шаблон.** Содержит `<your-dockerhub>` placeholder, push не настроен. Для реального релиза нужен registry + CI.
- **Нет CI/CD.** Сборка/тесты/деплой — вручную (`build-all.sh`, `k8s/install.sh`). Для продакшена — GitHub Actions / GitLab CI + ArgoCD.
- **Нет Helm-чарта.** Только raw `kubectl apply -f`. Параметризация (образы, ресурсы, секреты) — через `kustomize` или Helm.
- **`replicas: 1` у всех сервисов.** Нет горизонтального масштабирования. Для order/billing это требует решения п. 10 (optimistic lock) и п. 4 (Kafka cluster).

---

## Что реализовано, несмотря на ограничения

Чтобы картина была честной — **есть**:

- **Идемпотентность consumer'ов:** `processed_orders` (billing), `user_email_projection` (notification), статус-машина `NEW → PAID/FAILED` (order).
- **Единый формат событий** (`common-events`) — producer и consumer не расходятся по типам.
- **Stateless JWT** — горизонтальное масштабирование user-service без session store.
- **JSON-логи** с MDC — база для будущей агрегации.
- **Actuator + Micrometer** — база для будущего мониторинга.
- **Readiness/liveness** — k8s не отправляет трафик в непрогретый под.
- **Liquibase** — детерминированная схема БД.
- **Multi-stage Dockerfile** — non-root runtime, один образ на 4 сервиса через `ARG MODULE`.
- **E2E-покрытие** happy + failure path через Newman.

Эти ограничения — карта следующего этапа развития, а не баги. Каждый пункт имеет локальное решение, но требует выхода за рамки ДЗ.