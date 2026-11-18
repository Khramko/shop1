# 📡 API

Публичное HTTP API Shop Stream App. Полная OpenAPI-спецификация — [`docs/api.yaml`](api.yaml) (OpenAPI 3.0.3). Базовый URL: `http://arch.homework` (или `http://localhost:8080` для Docker Compose).

Все эндпоинты, кроме `/health`, `/actuator/**` и `/auth/**`, требуют **JWT** в заголовке `Authorization: Bearer <token>`. Токен выдаётся при регистрации/логине, TTL — 1 час (`security.jwt.expiration-ms: 3600000`), алгоритм HS256.

Ошибки возвращаются в едином JSON-формате (`GlobalExceptionHandler` из `common-logging`):

```json
{ "error": "validation", "details": "email: must be a well-formed email address" }
{ "error": "forbidden" }
{ "error": "internal_error" }
```

Коды: `400` — валидация, `401` — нет/просрочен токен, `403` — чужой ресурс, `409` — конфликт (email занят, idempotency_in_progress), `500` — внутренняя ошибка.

---

## User Service (`:8081`)

### `POST /auth/register`

Регистрация. Публикует `user.created` (создаёт аккаунт в billing и проекцию email в notification). Возвращает JWT.

**Request:**

```json
{
  "name": "User zxskyb99",
  "email": "user_zxskyb99@example.com",
  "password": "password123"
}
```

Валидация: `name` — `@NotBlank`, `email` — `@NotBlank @Email`, `password` — `@NotBlank @Size(min=6)`.

**Response `201 Created`:**

```json
{
  "id": 5,
  "token": "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiI1IiwiZW1haWwiOiJ1c2VyX3p4c2t5Yjk5QGV4YW1wbGUuY29tIiwiaWF0IjoxNzk0NDk4Mzg2LCJleHAiOjE3OTQ1MDE5ODZ9.0h3XSSTkw_NCcqEGHrDqdx4Tnsr6Efky0Fo13EmGmsg"
}
```

**Response `409 Conflict`:** `{"error":"Email already exists"}`.

### `POST /auth/login`

Логин. Не публикует событий.

**Request:**

```json
{ "email": "user_zxskyb99@example.com", "password": "password123" }
```

**Response `200 OK`:** `{ "id": 5, "token": "..." }`
**Response `401`:** `{"error":"Invalid credentials"}`.

### `GET /api/profile`

Свой профиль. `id` берётся из JWT (`@AuthenticationPrincipal AuthenticatedUser principal`), не из пути.

**Response `200 OK`:**

```json
{ "id": 5, "name": "User zxskyb99", "email": "user_zxskyb99@example.com" }
```

### `PUT /api/profile`

Обновить имя. Email не меняется.

**Request:**

```json
{ "name": "New Name" }
```

**Response `200 OK`:** `{ "id": 5, "name": "New Name", "email": "..." }`

---

## Billing Service (`:8082`)

### `GET /billing/accounts/me`

Текущий аккаунт пользователя из JWT. Аккаунт создаётся асинхронно из `user.created`, поэтому сразу после регистрации возможен `500` — нужен polling.

**Response `200 OK`:**

```json
{ "accountId": 5, "userId": 5, "balance": 0.00 }
```

`balance` — `NUMERIC(19,2)`, в JSON сериализуется как число.

### `POST /billing/accounts/me/deposit`

Пополнить баланс. Синхронно обновляет `accounts.balance`.

**Request:**

```json
{ "amount": 1000 }
```

Валидация: `amount` — `@NotNull @DecimalMin("0.01")`.

**Response `200 OK`:**

```json
{ "accountId": 5, "userId": 5, "balance": 1000.00 }
```

---

## Order Service (`:8083`)

### `POST /orders`

Создать заказ. Возвращает `202 Accepted` — фактическое списание и переход в `PAID`/`FAILED` происходят асинхронно через Saga (Kafka): `funds.reserve.command` → `funds.reserved` → `stock.reserve.command` → `stock.reserved` → `courier.reserve.command` → `courier.reserved` → `order.paid`. При провале любого шага запускается компенсация (`compensating` → `failed`).

**Request:**

```json
{
  "price": 300,
  "sku": "SKU-001",
  "qty": 2,
  "deliverySlot": "2026-11-16T10:00"
}
```

Валидация: `price` — `@NotNull @DecimalMin("0.01")`. В режиме Saga (`saga.enabled=true`, по умолчанию) поля `sku`, `qty`, `deliverySlot` обязательны — иначе `400 validation`. В режиме v1 (`saga.enabled=false`) достаточно `price`.

**Response `202 Accepted`:**

```json
{
  "orderId": 3,
  "userId": 5,
  "price": 300.00,
  "sku": "SKU-001",
  "qty": 2,
  "deliverySlot": "2026-11-16T10:00",
  "status": "RESERVING",
  "createdAt": "2026-11-12T15:46:27.088427395Z"
}
```

#### Идемпотентность

Если передан заголовок `Idempotency-Key: <string>`, вызов становится идемпотентным:

- Первый вызов с данным ключом создаёт заказ и сохраняет ответ в `idempotency_keys`.
- Повторный вызов с **тем же** ключом (и тем же `userId`) возвращает **сохранённый** ответ (тот же `orderId`, тот же `status`) с заголовком `X-Idempotent-Replay: true`. Новый заказ **не создаётся**.
- Если первый запрос с этим ключом ещё выполняется (placeholder вставлен, результат
  ещё не заполнен), второй получает `409 idempotency_in_progress`. Клиент должен
  повторить с тем же ключом через 1–2 с. Это отличается от `202` с
  `X-Idempotent-Replay: true`, который означает «результат уже готов».
- Механика: `tryInsert` в `REQUIRES_NEW` застолбляет ключ до создания заказа,
  `updateResult` заполняет результат, `awaitFilled` ждёт до 5 с при гонке.
  Подробнее — [`docs/idempotency.md`](idempotency.md).
- Ключ уникален в рамках пользователя: `UNIQUE(user_id, idempotency_key)`.
- TTL ключа — 24 часа, затем удаляется фоновой задачей.

Без заголовка `Idempotency-Key` каждый вызов создаёт новый заказ (поведение обратной совместимости).

**Пример:**

```bash
curl -X POST "$BASE/orders" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: 7f3c1a9e-2b4d-4e8f-9a1c-5d6e7f8a9b0c" \
  -d '{"sku":"SKU-001","qty":1,"deliverySlot":"2026-11-16T10:00","price":100}'
# → 202 {"orderId":42,...,"status":"RESERVING"}
# повтор с тем же ключом:
# → 202 {"orderId":42,...} + X-Idempotent-Replay: true
```

### `GET /orders/{id}`

Текущий статус заказа. `userId` берётся из JWT; запрос чужого заказа → `403`.

**Response `200 OK`:**

```json
{
  "orderId": 3,
  "userId": 5,
  "price": 300.00,
  "sku": "SKU-001",
  "qty": 2,
  "deliverySlot": "2026-11-16T10:00",
  "status": "PAID",
  "createdAt": "2026-11-12T15:46:27.088427395Z"
}
```

`status` — состояние заказа. В режиме Saga (`saga.enabled=true`, по умолчанию):

- `RESERVING` — сага стартовала, идёт последовательный резерв funds → stock → courier.
- `PAID` — все три шага успешны, получен `courier.reserved`, опубликован `order.paid`.
- `COMPENSATING` — один из шагов провалился, идёт откат уже зарезервированных ресурсов.
- `FAILED` — компенсация завершена, заказ отменён, опубликован `order.failed`.
- `COMPENSATION_FAILED` — компенсация не удалась (например, `funds.release.failed`), требуется ручное вмешательство.

В режиме v1 (`saga.enabled=false`) статусы: `NEW → PAID | FAILED` (по событиям `funds.withdrawn` / `funds.withdraw.failed`).

**Response `403 Forbidden`** — заказ принадлежит другому пользователю.

### `GET /orders`

Список заказов текущего пользователя, отсортирован по `createdAt DESC`.

**Response `200 OK`:** массив `OrderResponse`.

---

## Notification Service (`:8084`)

### `GET /notifications`

Уведомления текущего пользователя, `createdAt DESC`. Создаются из `order.paid` (HAPPY) и `order.failed` (ANGRY).

**Response `200 OK`:**

```json
[
  {
    "id": 5,
    "userId": 6,
    "orderId": 5,
    "type": "HAPPY",
    "subject": "Заказ #5 оплачен",
    "body": "Здравствуйте! Ваш заказ #5 на сумму 300.00 успешно оплачен. Письмо счастья отправлено на user_bxevm043@example.com.",
    "createdAt": "2026-11-14T17:01:24.237032Z"
  },
  {
    "id": 6,
    "userId": 6,
    "orderId": 6,
    "type": "ANGRY",
    "subject": "Заказ #6 отклонён",
    "body": "К сожалению, ваш заказ #6 на сумму 5000.00 не может быть выполнен. Причина: INSUFFICIENT_FUNDS. Письмо горя отправлено на user_bxevm043@example.com.",
    "createdAt": "2026-11-14T17:01:26.322184Z"
  }
]
```

`type` ∈ `HAPPY | ANGRY`. `body` содержит причину для ANGRY и email из проекции `user_email_projection` (fallback — `unknown@example.com`).

---

## Health

Каждый сервис отдаёт `GET /health` без аутентификации:

```json
{ "status": "OK", "service": "order-service" }
```

Используется в `readinessProbe`/`livenessProbe` в k8s и в healthcheck Docker Compose.

---

## Actuator / Prometheus

`GET /actuator/health`, `GET /actuator/info`, `GET /actuator/prometheus` — без аутентификации (`permitAll` в `SecurityConfig`). Метрики с тегом `application=<service-name>` и гистограммами `http.server.requests` (p50/p95/p99).

---

## Сводная таблица

| Метод | Путь | Сервис | Auth | Успех |
|---|---|---|---|---|
| `POST` | `/auth/register` | user | — | `201 {id, token}` |
| `POST` | `/auth/login` | user | — | `200 {id, token}` |
| `GET` | `/api/profile` | user | Bearer | `200 {id, name, email}` |
| `PUT` | `/api/profile` | user | Bearer | `200 {id, name, email}` |
| `GET` | `/billing/accounts/me` | billing | Bearer | `200 {accountId, userId, balance}` |
| `POST` | `/billing/accounts/me/deposit` | billing | Bearer | `200 {accountId, userId, balance}` |
| `POST` | `/orders` | order | Bearer | `202 {orderId, status: RESERVING}` (saga) / `NEW` (v1) |
| `GET` | `/orders/{id}` | order | Bearer | `200 {orderId, status: RESERVING/PAID/COMPENSATING/FAILED/COMPENSATION_FAILED}` |
| `GET` | `/orders` | order | Bearer | `200 [OrderResponse]` |
| `GET` | `/notifications` | notification | Bearer | `200 [NotificationResponse]` |
| `GET` | `/health` | все | — | `200 {status, service}` |
| `GET` | `/actuator/*` | все | — | `200` |

---

## Пример: полный сценарий через `curl`

```bash
BASE=http://arch.homework

# 1. Регистрация
REG=$(curl -s -X POST "$BASE/auth/register" \
  -H 'Content-Type: application/json' \
  -d '{"name":"Test","email":"test_'$RANDOM'@example.com","password":"password123"}')
TOKEN=$(echo "$REG" | jq -r .token)

# 2. Polling аккаунта (создаётся из user.created)
for i in $(seq 1 10); do
  ACC=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/billing/accounts/me" \
        -H "Authorization: Bearer $TOKEN")
  [ "$ACC" = "200" ] && break
  sleep 0.5
done
curl -s "$BASE/billing/accounts/me" -H "Authorization: Bearer $TOKEN"

# 3. Депозит
curl -s -X POST "$BASE/billing/accounts/me/deposit" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"amount":1000}'

# 4. Успешный заказ (saga: funds → stock → courier)
OID=$(curl -s -X POST "$BASE/orders" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"sku":"SKU-001","qty":2,"deliverySlot":"2026-11-16T10:00","price":300}' | jq -r .orderId)
for i in $(seq 1 20); do
  S=$(curl -s "$BASE/orders/$OID" -H "Authorization: Bearer $TOKEN" | jq -r .status)
  [ "$S" = "PAID" ] && break
  sleep 0.5
done
echo "order $OID → $S"        # PAID

# 5. Неуспешный заказ (нехватка средств)
OID2=$(curl -s -X POST "$BASE/orders" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"sku":"SKU-001","qty":1,"deliverySlot":"2026-11-16T12:00","price":5000}' | jq -r .orderId)
for i in $(seq 1 20); do
  S2=$(curl -s "$BASE/orders/$OID2" -H "Authorization: Bearer $TOKEN" | jq -r .status)
  [ "$S2" = "FAILED" ] && break
  sleep 0.5
done
echo "order $OID2 → $S2"       # FAILED

# 6. Идемпотентность
IDEM_KEY="demo-$(date +%s)"
curl -s -X POST "$BASE/orders" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $IDEM_KEY" \
  -d '{"sku":"SKU-001","qty":1,"deliverySlot":"2026-11-17T10:00","price":100}'
curl -s -X POST "$BASE/orders" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $IDEM_KEY" \
  -d '{"sku":"SKU-001","qty":1,"deliverySlot":"2026-11-17T10:00","price":100}' \
  -i | grep -i X-Idempotent-Replay     # → X-Idempotent-Replay: true

# 7. Итоговое состояние
curl -s "$BASE/billing/accounts/me" -H "Authorization: Bearer $TOKEN"   # balance: 700
curl -s "$BASE/notifications" -H "Authorization: Bearer $TOKEN"         # HAPPY + ANGRY
```

Тот же сценарий автоматизирован в [`etc/shop-stream-app.postman_collection.json`](../etc/shop-stream-app.postman_collection.json) и [`etc/shop-saga.postman_collection.json`](../etc/shop-saga.postman_collection.json), запуск — `./run-newman.sh`.

---

## Модели

### `Account`

```json
{ "accountId": 5, "userId": 5, "balance": 700.00 }
```

### `Order`

```json
{
  "orderId": 3,
  "userId": 5,
  "price": 300.00,
  "sku": "SKU-001",
  "qty": 2,
  "deliverySlot": "2026-11-16T10:00",
  "status": "PAID",
  "createdAt": "2026-11-12T15:46:27.088427395Z"
}
```

`status` ∈ `RESERVING | PAID | COMPENSATING | FAILED | COMPENSATION_FAILED` (saga) или `NEW | PAID | FAILED` (v1). `sku`, `qty`, `deliverySlot` — `null` для v1-заказов.

### `Notification`

```json
{
  "id": 5,
  "userId": 6,
  "orderId": 5,
  "type": "HAPPY",
  "subject": "Заказ #5 оплачен",
  "body": "...",
  "createdAt": "2026-11-14T17:01:24.237032Z"
}
```

### `AuthResponse`

```json
{ "id": 5, "token": "..." }
```

### `UserResponse`

```json
{ "id": 5, "name": "User zxskyb99", "email": "user_zxskyb99@example.com" }
```

---

## Аутентификация

Stateless JWT (HS256). Секрет — `JWT_SECRET_KEY` (минимум 32 символа), TTL — `security.jwt.expiration-ms`.

Payload:

```json
{
  "sub": "5",
  "email": "user_zxskyb99@example.com",
  "iat": 1794498386,
  "exp": 1794501986
}
```

`JwtAuthenticationFilter` (`common-security`) валидирует подпись, кладёт `AuthenticatedUser(id, email)` в `SecurityContext`. Контроллеры получают principal через `@AuthenticationPrincipal`; `id` из токена — единственный источник идентичности (никогда не берётся из `path`/`body`).

**Logout** не реализован (stateless JWT). Клиент удаляет токен локально.

---

## Зарезервированные эндпоинты (legacy)

В `user-service` исторически были `/api/users/**` (CRUD) — в текущей версии закрыты `denyAll` для обычных пользователей. Legacy-коллекция `etc/collection.json` помечена deprecated; актуальные — `etc/shop-stream-app.postman_collection.json` и `etc/shop-saga.postman_collection.json`.