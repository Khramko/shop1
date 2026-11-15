# Shop Stream App

Микросервисный интернет-магазин на Spring Boot 3.2 + Kafka + PostgreSQL. Демонстрирует **Event Collaboration**, **Orchestration Saga** с компенсациями и **идемпотентность HTTP** через `Idempotency-Key`.

---

## 📋 Содержание

- [Требования ДЗ](#-требования-дз)
    - [ДЗ №1: Распределённые транзакции](#дз-1-распределённые-транзакции)
    - [ДЗ №2: Идемпотентность](#дз-2-идемпотентность)
- [Архитектура](#-архитектура)
- [Быстрый старт](#-быстрый-старт)
- [Установка в Kubernetes](#-установка-в-kubernetes)
- [Тесты в Postman](#-тесты-в-postman)
- [Документация](#-документация)
- [Структура проекта](#-структура-проекта)

---

## 🎯 Требования ДЗ

###  Распределённые транзакции

> Реализовать сервисы «Биллинг», «Склад», «Доставка». Для сервиса «Заказ» в методе «создание заказа» реализовать механизм распределённой транзакции (на основе Саги или двухфазного коммита). Во время создания заказа: убедиться, что платёж прошёл (биллинг), зарезервировать товар (склад), зарезервировать курьера на слот (доставка). Если хотя бы один пункт не получилось — откатить все остальные изменения.

#### (0) Паттерн: Orchestration Saga

**Выбран Orchestration Saga** (централизованный оркестратор + хореография шагов через Kafka).

**Почему не Choreography:** три участника (billing → warehouse → delivery) с жёстким порядком шагов; нужна явная точка принятия решения о компенсации; состояние саги должно переживать рестарт.

**Как реализовано:**

- `SagaOrchestrator` в `order-service` управляет последовательностью: `funds.reserve.command` → `funds.reserved` → `stock.reserve.command` → `stock.reserved` → `courier.reserve.command` → `courier.reserved` → `order.paid`.
- Состояние — в таблице `saga_states (order_id PK, step, failure_reason, updated_at)`.
- При провале любого шага — компенсация в обратном порядке:
    - провал billing (`STARTED`) — ничего не откатываем;
    - провал warehouse (`FUNDS_RESERVED`) — `funds.release.command`;
    - провал delivery (`STOCK_RESERVED`) — `stock.release.command` → после `stock.released` цепочкой `funds.release.command`.
- Курьер не освобождается при провале delivery — по контракту `DeliveryService.reserve` бросает исключение до сохранения `Reservation`.

**Подробнее:** [`docs/patterns.md`](docs/patterns.md) → «ДЗ №1», [`docs/theory.md`](docs/theory.md) → §6, [`docs/kafka-topics.md`](docs/kafka-topics.md).

**Сервисы:**

| Сервис | Порт | Роль в саге |
|---|---|---|
| `billing-service` | 8082 | резерв/освобождение funds (`accounts`, `reserved_funds`) |
| `warehouse-service` | 8085 | резерв/освобождение товара (`stock`, `reservations`) |
| `delivery-service` | 8086 | резерв/освобождение курьера (`courier_slots`, `reservations`) |
| `order-service` | 8083 | оркестратор (`SagaOrchestrator`, `saga_states`) |

#### (1) Команда установки + namespace

**Namespace:** `shop`. Создаётся автоматически в `install.sh` (первым шагом).

**Kubernetes:**

```bash
# 1. Создать namespace (опционально — install.sh сделает это сам)
kubectl create namespace shop

# 2. Установить всё (postgres, kafka, 6 сервисов, ingress)
cd k8s
./install.sh

# 3. Проверить
kubectl get pods -n shop
kubectl get ingress -n shop

# 4. Удалить
cd k8s
./uninstall.sh
```

`install.sh` последовательно применяет:

1. `namespace.yaml`
2. `common-configmap.yaml`, `common-secrets.yaml`
3. `postgres.yaml` → `rollout status statefulset/postgres` + `job/postgres-init-dbs` (создаёт 6 БД)
4. `kafka.yaml` → `rollout status statefulset/kafka` (KRaft, startup дольше)
5. `user-service/`, `billing-service/`, `order-service/`, `notification-service/`, `warehouse-service/`, `delivery-service/`
6. `ingress.yaml`

**Docker Compose (локально, без k8s):**

```bash
./build-all.sh dev        # собрать 6 образов
docker compose up -d      # postgres + kafka + 6 сервисов + nginx-gateway
```

**Требования к хосту:** добавить `127.0.0.1 arch.homework` в `/etc/hosts`.

#### (2) Тесты в Postman

**Коллекция:** [`etc/shop-saga.postman_collection.json`](etc/shop-saga.postman_collection.json)

- **`{{baseUrl}}` = `http://arch.homework`** (initial value в `variable` коллекции).
- **20 запросов**, **34 ассерта**.
- Покрывает: happy path (A01–A06), провал billing (B01–B03), провал warehouse с компенсацией funds (C01–C05), идемпотентность чтения (D01), идемпотентность создания (E01–E03).

**Запуск:**

```bash
./run-newman.sh --saga                     # baseUrl=http://arch.homework
./run-newman.sh --saga http://localhost:8080  # против Compose
```

**Что проверяется:**

| Сценарий | Шаги | Проверки |
|---|---|---|
| Happy path | A01–A06 | регистрация → deposit 1000 → заказ 300 → `PAID` → баланс 700 |
| Провал billing | B01–B03 | заказ 99999 → `FAILED` → баланс 700 (не списан) |
| Провал warehouse | C01–C05 | заказ 9999 шт → `FAILED` → баланс 700 (компенсация funds), stock SKU-003 = 100, ANGRY с `STOCK` |
| Идемпотентность | D01, E01–E03 | повторный `GET` → `PAID`; повторный `POST` с тем же `Idempotency-Key` → `X-Idempotent-Replay: true`, тот же `orderId` |

---

### ДЗ Идемпотентность

> Создать сервис «Заказ» и для метода «создание заказа» сделать идемпотентным.

#### (0) Паттерн: Idempotency-Key

**Выбран Idempotency-Key** (клиентский ключ + персистентный журнал ответов).

**Почему не дедупликация по телу:** тело может содержать `deliverySlot` в разном формате; хеш не отличает «клиент повторил» от «клиент создал два одинаковых заказа намеренно».

**Почему не unique constraint на `orders`:** у заказа нет естественного бизнес-ключа; два одинаковых заказа — легитимный сценарий.

**Как реализовано:**

1. Клиент передаёт `Idempotency-Key: <uuid>` в заголовке `POST /orders`.
2. `OrderService.createIdempotent()` ищет пару `(user_id, idempotency_key)` в `idempotency_keys`.
3. Если найдена — возвращает сохранённые `http_status` и `response_body` с заголовком `X-Idempotent-Replay: true`. Новый заказ **не создаётся**.
4. Если не найдена — создаёт заказ (через `createSaga`), сериализует ответ и сохраняет в `idempotency_keys`.
5. Гонка (два параллельных запроса с одним ключом) — ловится `DataIntegrityViolationException` по `uq_idempotency_user_key`, затем читается уже сохранённая запись и возвращается как replay.
6. TTL ключа — 24 часа, `IdempotencyKeyCleanupJob` (`@Scheduled(cron = "0 0 * * * *")`) удаляет старые.

**Идемпотентность consumer'ов** (Kafka at-least-once):

| Сервис | Таблица-маркер |
|---|---|
| billing | `processed_orders` (v1), `reserved_funds` (saga) |
| warehouse | `reservations` (order_id PK) |
| delivery | `reservations` (order_id PK) |
| order | `SagaState.step` (saga), `status != NEW` (v1) |

**Подробнее:** [`docs/patterns.md`](docs/patterns.md) → «ДЗ №2», [`docs/api.md`](docs/api.md) → «Идемпотентность».

#### (1) Команда установки + namespace

Та же, что для ДЗ №1 — namespace `shop`, `k8s/install.sh` или `docker compose up -d`.

#### (2) Тесты в Postman

**Коллекция:** [`etc/shop-saga.postman_collection.json`](etc/shop-saga.postman_collection.json), шаги **E01–E03**.

- **`{{baseUrl}}` = `http://arch.homework`**.
- **E01** — первый `POST /orders` с `Idempotency-Key` (генерируется в pre-request) → `202`, нет `X-Idempotent-Replay`.
- **E02** — повтор с тем же ключом → `202`, `X-Idempotent-Replay: true`, тот же `orderId`, тело байт-в-байт.
- **E03** — `GET /orders/{id}` → ровно один заказ, а не дубль.

**Запуск:**

```bash
./run-newman.sh --saga
```

---

## 🏗 Архитектура

**6 микросервисов + 4 общие библиотеки.**

| Сервис | Порт | Назначение |
|---|---|---|
| `user-service` | 8081 | регистрация, логин, профиль |
| `billing-service` | 8082 | аккаунты, депозит, резерв/списание funds |
| `order-service` | 8083 | создание/статус заказов, **SagaOrchestrator**, `Idempotency-Key` |
| `notification-service` | 8084 | HAPPY/ANGRY уведомления |
| `warehouse-service` | 8085 | резерв/освобождение товара |
| `delivery-service` | 8086 | резерв/освобождение курьера на слот |

**Общие библиотеки:**

- `common-events` — `Topics`, `SagaEvents`, v1-события.
- `common-security` — `JwtService`, `JwtAuthenticationFilter`, `SecurityConfig`.
- `common-logging` — `RequestLoggingFilter`, `GlobalExceptionHandler`.
- `common-dto` — (не выделен; DTO в каждом сервисе).

**Инфраструктура:**

- PostgreSQL 16 (6 БД: `user_db`, `billing_db`, `order_db`, `notification_db`, `warehouse_db`, `delivery_db`).
- Apache Kafka 4.1.1 (KRaft, single-node).
- nginx-ingress / nginx-gateway (`arch.homework`).

**Подробнее:** [`docs/architecture.md`](docs/architecture.md), [`docs/services.md`](docs/services.md), [`docs/kafka-topics.md`](docs/kafka-topics.md).

---

## 🚀 Быстрый старт

### Docker Compose

```bash
./build-all.sh dev
docker compose up -d

# проверка
curl -s http://localhost:8080/health
```

### Kubernetes

```bash
# 1. nginx-ingress (если нет)
helm repo add ingress-nginx https://kubernetes.github.io/ingress-nginx
helm repo update
helm install nginx-ingress ingress-nginx/ingress-nginx -n ingress-nginx --create-namespace

# 2. установка
cd k8s && ./install.sh

# 3. hosts
echo "127.0.0.1 arch.homework" | sudo tee -a /etc/hosts

# 4. проверка
curl -s http://arch.homework/health
```

**Подробнее:** [`docs/quickstart.md`](docs/quickstart.md).

---

## 🧪 Тесты в Postman

Две коллекции:

| Коллекция | Что проверяет | Запросов | Ассертов |
|---|---|---|---|
| `etc/shop-stream-app.postman_collection.json` | Event Collaboration (v1) | 11 | 22 |
| `etc/shop-saga.postman_collection.json` | Saga + идемпотентность | 20 | 34 |

**Запуск:**

```bash
./run-newman.sh                          # stream-app, baseUrl=http://arch.homework
./run-newman.sh --saga                   # saga, baseUrl=http://arch.homework
./run-newman.sh --both                   # обе подряд
./run-newman.sh --saga http://localhost:8080  # против Compose
```

Отчёты — в `reports/newman-<label>-<timestamp>.json`.

**Подробнее:** [`docs/testing.md`](docs/testing.md).

---

## 📚 Документация

| Файл | Что внутри |
|---|---|
| [`docs/api.md`](docs/api.md) / [`docs/api.yaml`](docs/api.yaml) | HTTP API (OpenAPI 3.0.3) |
| [`docs/architecture.md`](docs/architecture.md) | стек, структура, схемы взаимодействия |
| [`docs/kafka-topics.md`](docs/kafka-topics.md) | 20+ топиков, payload, sequence-диаграммы |
| [`docs/patterns.md`](docs/patterns.md) | **Saga** и **Idempotency-Key** (требования ДЗ п. 0) |
| [`docs/theory.md`](docs/theory.md) | сравнение вариантов (HTTP, Event Collab, Saga) |
| [`docs/services.md`](docs/services.md) | описание 6 сервисов |
| [`docs/testing.md`](docs/testing.md) | Postman/Newman, шаги E01–E03 |
| [`docs/quickstart.md`](docs/quickstart.md) | запуск (Compose + k8s) |
| [`docs/monitoring.md`](docs/monitoring.md) | Prometheus/Grafana |
| [`docs/limitations.md`](docs/limitations.md) | 17 известных ограничений |
| [`docs/technologies.md`](docs/technologies.md) | дубликат `architecture.md` (оставлен для совместимости) |

---

## 📁 Структура проекта

```
shop/
├── pom.xml                          # parent POM (multi-module)
├── Dockerfile                       # единый для 6 сервисов (ARG MODULE)
├── build-all.sh                     # сборка 6 образов
├── run-newman.sh                    # E2E-прогон (stream / saga / both)
├── docker-compose.yml               # локальный стек
├── docker/
│   ├── nginx-gateway.conf
│   └── init-db.sql
├── k8s/
│   ├── install.sh / uninstall.sh
│   ├── namespace.yaml
│   ├── common-configmap.yaml / common-secrets.yaml
│   ├── postgres.yaml / kafka.yaml / ingress.yaml
│   ├── user-service/       {deployment,service}.yaml
│   ├── billing-service/
│   ├── order-service/
│   ├── notification-service/
│   ├── warehouse-service/
│   └── delivery-service/
├── common/
│   ├── common-events/      # Topics, SagaEvents
│   ├── common-security/    # JWT
│   └── common-logging/     # RequestLoggingFilter, GlobalExceptionHandler
├── user-service/           # :8081
├── billing-service/        # :8082
├── order-service/          # :8083 — SagaOrchestrator
├── notification-service/   # :8084
├── warehouse-service/      # :8085
├── delivery-service/       # :8086
├── etc/
│   ├── shop-stream-app.postman_collection.json
│   ├── shop-saga.postman_collection.json
│   └── shop-local.postman_environment.json
├── docs/
│   ├── api.md / api.yaml
│   ├── architecture.md
│   ├── kafka-topics.md
│   ├── limitations.md
│   ├── monitoring.md
│   ├── patterns.md
│   ├── quickstart.md
│   ├── services.md
│   ├── testing.md
│   ├── theory.md
│   └── technologies.md
└── reports/                # newman-*.json
```

---

## 🔑 Ключевые эндпоинты

| Метод | Путь | Назначение |
|---|---|---|
| `POST` | `/auth/register` | регистрация |
| `POST` | `/auth/login` | логин |
| `GET` / `PUT` | `/api/profile` | профиль |
| `GET` | `/billing/accounts/me` | баланс |
| `POST` | `/billing/accounts/me/deposit` | пополнение |
| `POST` | `/orders` | создать заказ (saga, `Idempotency-Key`) |
| `GET` | `/orders/{id}` | статус заказа |
| `GET` | `/orders` | список заказов |
| `GET` | `/notifications` | уведомления |
| `GET` | `/warehouse/stock` | склад |
| `GET` | `/delivery/slots` | слоты курьеров |
| `GET` | `/health` | health (все сервисы) |

**Полная спецификация:** [`docs/api.md`](docs/api.md), [`docs/api.yaml`](docs/api.yaml).

---

## ⚠️ Известные ограничения

17 пунктов — в [`docs/limitations.md`](docs/limitations.md). Ключевые:

- Нет Outbox pattern (событие может потеряться между `save()` и `kafka.send()`).
- Нет retry / DLQ.
- Нет таймаута в `SagaOrchestrator` (сага «зависает» при потере ответа).
- Kafka и Postgres — single-node.
- Нет distributed tracing, централизованных логов, бизнес-метрик.
- Нет юнит-тестов (только E2E через Newman).

---

## 🛠 Технологии

Java 17, Spring Boot 3.2.5, Spring Kafka, PostgreSQL 16, Apache Kafka 4.1.1 (KRaft), Liquibase, JJWT 0.12.5, Micrometer, Logstash Logback Encoder 7.4, Docker, Kubernetes, nginx-ingress.

**Подробнее:** [`docs/architecture.md`](docs/architecture.md).