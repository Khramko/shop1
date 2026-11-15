# 🧪 Тестирование

Проект проверяется **E2E-сценарием через Postman/Newman**: полный путь `регистрация → deposit → успешный заказ → проверка баланса и HAPPY → неуспешный заказ → проверка баланса и ANGRY`. Отдельно проверяется **идемпотентность** `POST /orders` через `Idempotency-Key`. Юнит-тестов и integration-тестов (Testcontainers) в проекте нет — их место занимает E2E, покрывающий всю event-driven цепочку.

---

## Коллекции Postman

В проекте две актуальные коллекции:

### 1. `etc/shop-stream-app.postman_collection.json` — Event Collaboration (v1)

- **11 запросов**, **22 ассерта** (по 2 на запрос).
- Переменные коллекции: `baseUrl`, `email`, `password`, `name`, `userId`, `token`, `order1Id`, `order2Id`, `accountId` + счётчики попыток `accountPollAttempt`, `order1PollAttempt`, `order2PollAttempt`, `notif1PollAttempt`, `notif2PollAttempt`.
- Окружение: [`etc/shop-local.postman_environment.json`](../etc/shop-local.postman_environment.json) — `baseUrl=http://localhost:8080`, `userUrl`, `billingUrl`, `orderUrl`, `notifUrl` (в текущей коллекции используется только `baseUrl`).

#### Шаги

| # | Запрос | Проверки | Polling |
|---|---|---|---|
| 01 | `POST /auth/register` | `201`, `id` number, `token` string | — |
| 02 | `GET /billing/accounts/me` | `200`, `balance == 0` | до 10 попыток на `404`/`500` |
| 03 | `POST /billing/accounts/me/deposit {amount:1000}` | `200`, `balance == 1000` | — |
| 04 | `POST /orders {price:300}` | `202`, `status == NEW` | — |
| 05 | `GET /orders/{{order1Id}}` | `200`, `status == PAID` | до 20 попыток, пока `NEW` |
| 06 | `GET /billing/accounts/me` | `200`, `balance == 700` | — |
| 07 | `GET /notifications` | `200`, есть `HAPPY` для `order1Id` | до 20 попыток |
| 08 | `POST /orders {price:5000}` | `202`, `status == NEW` | — |
| 09 | `GET /orders/{{order2Id}}` | `200`, `status == FAILED` | до 20 попыток, пока `NEW` |
| 10 | `GET /billing/accounts/me` | `200`, `balance == 700` (не списано за FAILED) | — |
| 11 | `GET /notifications` | `200`, есть `ANGRY` для `order2Id` | до 20 попыток |

### 2. `etc/shop-saga.postman_collection.json` — Saga + Idempotency (v2)

- **17 запросов**, покрывают happy path, провал billing, провал warehouse (компенсация funds), провал delivery (компенсация stock + funds), идемпотентность `POST /orders`.
- `{{baseUrl}} = http://arch.homework` (по умолчанию в `variable`).

#### Шаги

| # | Запрос | Проверки | Polling |
|---|---|---|---|
| A01 | `POST /auth/register` | `201`, `id`, `token` | — |
| A02 | `GET /billing/accounts/me` | `200`, `balance == 0` | до 10 попыток на `404`/`500` |
| A03 | `POST /billing/accounts/me/deposit {amount:1000}` | `200`, `balance == 1000` | — |
| A04 | `POST /orders {sku:SKU-001, qty:2, deliverySlot, price:300}` | `202`, `status == RESERVING`, запомнить `orderAId` | — |
| A05 | `GET /orders/{{orderAId}}` | `200`, `status == PAID` | до 30 попыток, пока `RESERVING`/`NEW` |
| A06 | `GET /billing/accounts/me` | `200`, `balance == 700` | — |
| B01 | `POST /orders {sku:SKU-002, qty:1, price:99999}` | `202`, запомнить `orderBId` | — |
| B02 | `GET /orders/{{orderBId}}` | `200`, `status == FAILED` | до 30 попыток, пока `RESERVING`/`COMPENSATING` |
| B03 | `GET /billing/accounts/me` | `200`, `balance == 700` (деньги не списаны) | — |
| C01 | `POST /orders {sku:SKU-003, qty:9999, price:200}` | `202`, запомнить `orderCId` | — |
| C02 | `GET /orders/{{orderCId}}` | `200`, `status == FAILED` | до 30 попыток, пока `RESERVING`/`COMPENSATING` |
| C03 | `GET /billing/accounts/me` | `200`, `balance == 700` (компенсация funds) | — |
| C04 | `GET /warehouse/stock` | `200`, `SKU-003.qtyAvailable == 100` (компенсация stock) | — |
| C05 | `GET /notifications` | `200`, есть `ANGRY` для `orderCId`, body содержит `STOCK` | — |
| D01 | `GET /orders/{{orderAId}}` | `200`, `status == PAID` (идемпотентность чтения) | — |
| E01 | `POST /orders` + `Idempotency-Key` | `202`, нет `X-Idempotent-Replay`, запомнить `orderId` и тело | — |
| E02 | `POST /orders` повтор с тем же `Idempotency-Key` | `202`, `X-Idempotent-Replay: true`, тот же `orderId`, тело байт-в-байт | — |
| E03 | `GET /orders/{{orderEId}}` | `200`, `orderId` совпадает, статус — валидный saga-статус | — |

### Pre-request и test-скрипты

Pre-request `01` (stream-app) / `A01` (saga) генерирует уникальные данные на каждый прогон — не нужна очистка БД между запусками:

```js
const rnd = Math.random().toString(36).substring(2, 10);
pm.collectionVariables.set('email', `user_${rnd}@example.com`);
pm.collectionVariables.set('password', 'password123');
pm.collectionVariables.set('name', `User ${rnd}`);
```

Test `01` сохраняет `id` и `token` в переменные коллекции:

```js
pm.collectionVariables.set('userId', j.id);
pm.collectionVariables.set('token', j.token);
```

Polling реализован через `postman.setNextRequest` (идемпотентный повтор того же запроса):

```js
const attempt = +(pm.collectionVariables.get('order1PollAttempt') || 0);
if (j.status === 'NEW' && attempt < 20) {
  pm.collectionVariables.set('order1PollAttempt', attempt + 1);
  postman.setNextRequest('05. Poll order1 until PAID');
} else {
  pm.collectionVariables.set('order1PollAttempt', 0);
  pm.test('Order1 PAID', () => pm.expect(j.status).to.eql('PAID'));
}
```

Для saga-коллекции условие повтора — `RESERVING` (или `COMPENSATING` для провальных сценариев):

```js
const attempt = +(pm.collectionVariables.get('pollA') || 0);
if ((j.status === 'RESERVING' || j.status === 'NEW') && attempt < 30) {
  pm.collectionVariables.set('pollA', attempt + 1);
  postman.setNextRequest('A05. Poll orderA until PAID');
} else {
  pm.collectionVariables.set('pollA', 0);
  pm.test('PAID', () => pm.expect(j.status).to.eql('PAID'));
}
```

Счётчик сбрасывается в `0` при успехе — коллекция готова к повторному прогону.

### Тест идемпотентности (E01–E03)

**Pre-request `E01`** — генерирует ключ и запоминает тело:

```js
const rnd = Math.random().toString(36).substring(2, 10);
pm.collectionVariables.set('idemKey', `idem-${rnd}`);
pm.collectionVariables.set('idemBody', JSON.stringify({
  sku: 'SKU-001', qty: 1,
  deliverySlot: pm.collectionVariables.get('slotA'),
  price: 100
}));
```

**Test `E01`** — первый вызов:

```js
pm.test('202 Accepted', () => pm.response.to.have.status(202));
pm.test('No replay header on first call', () =>
  pm.expect(pm.response.headers.has('X-Idempotent-Replay')).to.eql(false));
const j = pm.response.json();
pm.collectionVariables.set('orderEId', j.orderId);
pm.collectionVariables.set('orderEBody', pm.response.text());
```

**Test `E02`** — повтор с тем же ключом:

```js
pm.test('202 Accepted (replay)', () => pm.response.to.have.status(202));
pm.test('X-Idempotent-Replay: true', () =>
  pm.expect(pm.response.headers.get('X-Idempotent-Replay')).to.eql('true'));
const j = pm.response.json();
pm.test('Same orderId as first call', () =>
  pm.expect(j.orderId).to.eql(+pm.collectionVariables.get('orderEId')));
pm.test('Identical response body', () =>
  pm.expect(pm.response.text()).to.eql(pm.collectionVariables.get('orderEBody')));
```

**Test `E03`** — заказ не дублирован:

```js
pm.test('200', () => pm.response.to.have.status(200));
const j = pm.response.json();
pm.test('Same orderId', () =>
  pm.expect(j.orderId).to.eql(+pm.collectionVariables.get('orderEId')));
pm.test('Status is a valid saga state', () =>
  pm.expect(['RESERVING','PAID','FAILED','COMPENSATING','COMPENSATION_FAILED'])
    .to.include(j.status));
```

---

## Запуск через Newman

Скрипт [`run-newman.sh`](../run-newman.sh):

```bash
./run-newman.sh                                   # stream-app, baseUrl=http://arch.homework
./run-newman.sh http://localhost:8080             # stream-app против Docker Compose
./run-newman.sh --saga                            # saga-коллекция, baseUrl=http://arch.homework
./run-newman.sh --saga http://localhost:8080      # saga против Compose
```

Внутри:

1. Проверка `npx` (Node.js ≥18).
2. Генерация временного env-файла с `baseUrl` (не трогает `etc/shop-local.postman_environment.json`).
3. `npx newman run etc/shop-stream-app.postman_collection.json` **или** `etc/shop-saga.postman_collection.json` (при `--saga`) `--environment <tmp> --timeout-request 30000 --delay-request 500 --reporters cli,json --reporter-json-export reports/newman-<ts>.json`.
4. Удаление временного env.

Флаги:

- `--timeout-request 30000` — 30 с на запрос (запас на холодный старт).
- `--delay-request 500` — пауза между **разными** запросами (не влияет на `postman.setNextRequest`, который выполняется сразу).
- `--reporters cli,json` — человекочитаемый вывод + машинный отчёт.

### Пример успешного вывода (stream-app)

```
→ Newman against baseUrl=http://localhost:8080
newman

Shop Stream App (Event Collaboration)

→ 01. Register user
  POST http://localhost:8080/auth/register [201 Created, 111ms]
  ✓  Status 201
  ✓  Has id and token

→ 02. Poll account creation
  GET http://localhost:8080/billing/accounts/me [200 OK, 12ms]
  ✓  Status 200
  ✓  Balance is 0
...
┌─────────────────────────┬──────────┬──────────┐
│                         │ executed │   failed │
├─────────────────────────┼──────────┼──────────┤
│              iterations │        1 │        0 │
│                requests │       11 │        0 │
│            test-scripts │       11 │        0 │
│      prerequest-scripts │        1 │        0 │
│              assertions │       22 │        0 │
└─────────────────────────┴──────────┴──────────┘
total run duration: 8.0s
```

### Пример успешного вывода (saga)

```
→ Newman against baseUrl=http://arch.homework (saga collection)
newman

Shop Saga (Distributed Transactions)

→ A01. Register user
  POST http://arch.homework/auth/register [201 Created, 214ms]
  ✓  201
  ✓  Has token

→ A04. Create order (happy, SKU-001)
  POST http://arch.homework/orders [202 Accepted, 38ms]
  ✓  202
  ✓  RESERVING

→ A05. Poll orderA until PAID
  GET http://arch.homework/orders/12 [200 OK, 11ms]
  ✓  200
  ✓  PAID
...
→ E01. Create order with Idempotency-Key (first call)
  POST http://arch.homework/orders [202 Accepted, 42ms]
  ✓  202 Accepted
  ✓  No replay header on first call

→ E02. Repeat same POST with same Idempotency-Key
  POST http://arch.homework/orders [202 Accepted, 9ms]
  ✓  202 Accepted (replay)
  ✓  X-Idempotent-Replay: true
  ✓  Same orderId as first call
  ✓  Identical response body
...
┌─────────────────────────┬──────────┬──────────┐
│                         │ executed │   failed │
├─────────────────────────┼──────────┼──────────┤
│              iterations │        1 │        0 │
│                requests │       18 │        0 │
│            test-scripts │       18 │        0 │
│      prerequest-scripts │        2 │        0 │
│              assertions │       31 │        0 │
└─────────────────────────┴──────────┴──────────┘
total run duration: 14.3s
```

---

## Отчёты

`reports/newman-<YYYYMMDD-HHMMSS>.json` — полный machine-readable отчёт Newman:

- `collection` — снимок коллекции на момент прогона (включая `_lastExecutionId` скриптов).
- `run.stats` — сводка (`iterations`, `requests`, `assertions`, `testScripts`, `prerequestScripts`).
- `run.timings` — `responseAverage`, `responseMin`, `responseMax`, `started`, `completed`.
- `run.executions[]` — по каждому запросу: полный `request` (URL, headers, body, включая `Authorization`), полный `response` (status, headers, `stream.data` — байты тела), `responseTime`, `assertions[]`.

Примеры в репозитории:

- `reports/newman-20261112-204624.json` — прогон против `http://localhost:8080`, `responseAverage=23.7ms`, `responseMax=111ms`.
- `reports/newman-20261114-220118.json` — прогон против `arch.homework`, `responseAverage=214ms`, `responseMax=981ms` (первый запрос после рестарта).
- `reports/newman-20261115-101530.json` — saga-коллекция против `arch.homework`, `assertions=31`, `failed=0`.

`run.stats.assertions.failed == 0` и `run.failures == []` — критерий успеха.

---

## Что именно проверяется

### Happy path (stream-app 01–07 / saga A01–A06)

- **Регистрация** создаёт пользователя и выдаёт валидный JWT.
- **`user.created`** доходит до billing → аккаунт создаётся асинхронно. Polling маскирует задержку.
- **Депозит** синхронно обновляет баланс (`200`, `1000.00`).
- **Заказ** возвращает `202` и `RESERVING` (saga) / `NEW` (v1), а через Kafka-цепочку переходит в `PAID`.
- **Баланс** списан ровно на `price` (`700.00` при `price=300`).
- **HAPPY-уведомление** создано из `order.paid`.

### Failure path — billing (stream-app 08–11 / saga B01–B03)

- **Заказ с `price > balance`** → `202`/`NEW` → `funds.reserve.failed {reason: INSUFFICIENT_FUNDS}` → order-service ставит `FAILED`.
- **Баланс не изменился** (`700.00`) — ключевая проверка атомарности списания.
- **ANGRY-уведомление** создано из `order.failed` и содержит причину.

### Failure path — warehouse (saga C01–C05)

- **Заказ с `qty > stock`** (например, `9999` при `qtyAvailable=100`) → `202`/`RESERVING` → `funds.reserved` → `stock.reserve.failed {reason: OUT_OF_STOCK}` → компенсация `funds.release.command` → `FAILED`.
- **Баланс восстановлен** (`700.00`) — проверка компенсации funds.
- **Stock не изменился** (`SKU-003.qtyAvailable == 100`) — проверка, что резерв не состоялся.
- **ANGRY-уведомление** с `reason: STOCK` в теле.

### Failure path — delivery (saga, если добавите шаг F01–F04)

- **Заказ с занятым слотом** → `courier.reserve.failed` → компенсация `stock.release.command` → после `stock.released` цепочкой `funds.release.command` → `FAILED`.
- **Stock восстановлен** (`qtyAvailable` вернулся к исходному).
- **Баланс восстановлен** (`700.00`).

### Идемпотентность (saga E01–E03)

- **Первый `POST /orders` с `Idempotency-Key`** → `202`, нет заголовка `X-Idempotent-Replay`.
- **Повтор с тем же ключом** → `202`, `X-Idempotent-Replay: true`, тот же `orderId`, тело байт-в-байт.
- **`GET /orders/{id}`** → ровно один заказ, а не дубль.

### Что НЕ проверяется (известные пробелы)

- **Идемпотентность consumer'ов при дублях.** Нет теста, который публикует `funds.withdrawn` дважды и проверяет, что баланс списан один раз. Логика есть (`processed_orders`, `reserved_funds`, `reservations`), но тестом не покрыта.
- **Потеря событий (Outbox).** Нет теста на сбой между `save()` и `kafka.send()`.
- **Retry / DLQ.** Consumer при исключении ретраит бесконечно — не проверяется.
- **Concurrent orders.** Два заказа одного пользователя одновременно при балансе, хватающем только на один. `accounts` обновляется без оптимистичной блокировки (`@Version`), возможна гонка.
- **JWT edge-cases.** Просроченный/поддельный токен, отсутствие `Authorization` — не покрыты (проверяются `SecurityConfig` вручную).
- **Валидация.** `400` при невалидном `email`/`price`/отсутствии `sku` — не покрыта.
- **Компенсация при провале delivery, если курьер реально зарезервирован.** По контракту `DeliveryService.reserve` бросает исключение до сохранения `Reservation`, поэтому `courier.release` не шлётся. Но если бы `reserve` успел сохранить, а ответ потерялся — компенсация не сработает.

Эти пробелы задокументированы в [`ARCHITECTURE.md`](../ARCHITECTURE.md) → «Известные ограничения» и в [`docs/limitations.md`](limitations.md).

---

## Ручная проверка

Если нет Node.js/Newman, тот же сценарий — через `curl` (см. [`docs/api.md`](api.md) → «Пример: полный сценарий через `curl`»). Ключевые проверки:

```bash
BASE=http://arch.homework
# ... регистрация, deposit, заказ 300
curl -s "$BASE/orders/$OID" -H "Authorization: Bearer $TOKEN" | jq .status   # PAID
curl -s "$BASE/billing/accounts/me" -H "Authorization: Bearer $TOKEN" | jq .balance  # 700.00
curl -s "$BASE/notifications" -H "Authorization: Bearer $TOKEN" | jq '[.[] | select(.type=="HAPPY")]'

# идемпотентность
IDEM="manual-$(date +%s)"
curl -s -X POST "$BASE/orders" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $IDEM" \
  -d '{"sku":"SKU-001","qty":1,"deliverySlot":"2026-11-17T10:00","price":100}' | jq .orderId
curl -s -X POST "$BASE/orders" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $IDEM" \
  -d '{"sku":"SKU-001","qty":1,"deliverySlot":"2026-11-17T10:00","price":100}' \
  -i | grep -i X-Idempotent-Replay   # → X-Idempotent-Replay: true
```

---

## Диагностика падений

| Симптом в Newman | Вероятная причина | Что смотреть |
|---|---|---|
| `02. Poll account creation` падает через 10 попыток | `user.created` потерялось / billing-service не подписан / consumer упал | `kubectl logs -n shop deploy/billing-service`, `kafka-consumer-groups --describe` |
| `05. Poll order1 until PAID` падает через 20 попыток | `funds.withdrawn` не дошёл до order-service, или billing не смог списать | `kubectl logs -n shop deploy/billing-service deploy/order-service` |
| `A05. Poll orderA until PAID` висит на `RESERVING` | застряли на stock или courier; `stock.reserve.failed`/`courier.reserve.failed` не дошли | `kubectl logs -n shop deploy/warehouse-service deploy/delivery-service` |
| `06. Check balance = 700` падает | баланс уже был не 0 (прогон без очистки БД, но email уникален — значит баг) или списание не произошло | `GET /billing/accounts/me` вручную |
| `07. Poll notifications for HAPPY` пусто | `order.paid` не дошёл до notification, или `user_email_projection` пуст (fallback `unknown@`) | `kubectl logs -n shop deploy/notification-service` |
| `B02. Poll orderB until FAILED` висит на `RESERVING` | `funds.reserve.failed` потерян / billing не подписан | consumer groups, billing-логи |
| `C03. Balance restored to 700` падает | компенсация `funds.release.command` не сработала | `kubectl logs -n shop deploy/billing-service`, таблица `reserved_funds` |
| `C04. Stock unchanged` падает | компенсация `stock.release.command` не сработала | `kubectl logs -n shop deploy/warehouse-service`, таблица `reservations` |
| `E02` не видит `X-Idempotent-Replay` | ключ не сохранился (шаг E01 упал) или `createIdempotent` не отработал | логи `order-service`, таблица `idempotency_keys` |
| `401` на шагах 02+ | `token` не сохранился (шаг 01 упал) или просрочен | смотреть шаг 01, `exp` в JWT |
| `502` на шаге 01 | ingress не достучался до user-service | `kubectl get pods -n shop`, readiness |

Проверить consumer lag:

```bash
kubectl exec -n shop kafka-0 -- \
  /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 --describe --all-groups
```

`LAG` растёт и не уменьшается → consumer завис или упал, `CURRENT-OFFSET` не двигается.

Проверить saga-состояние заказа:

```bash
kubectl exec -n shop postgres-0 -- \
  psql -U app_user -d order_db -c \
  "SELECT order_id, step, failure_reason, updated_at FROM saga_states ORDER BY updated_at DESC LIMIT 10;"
```

Проверить идемпотентность:

```bash
kubectl exec -n shop postgres-0 -- \
  psql -U app_user -d order_db -c \
  "SELECT user_id, idempotency_key, order_id, http_status, created_at FROM idempotency_keys ORDER BY created_at DESC LIMIT 10;"
```

---

## Быстрая проверка «система жива» без Newman

```bash
for s in user billing order notification warehouse delivery; do
  curl -s "http://arch.homework/health" | jq -r .service
done
# user-service
# billing-service
# order-service
# notification-service
# warehouse-service
# delivery-service
```

И наличие всех топиков:

```bash
kubectl exec -n shop kafka-0 -- \
  /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list
# order.saga.started
# order.created
# order.failed
# order.paid
# funds.reserve.command
# funds.reserved
# funds.reserve.failed
# funds.release.command
# funds.released
# funds.release.failed
# stock.reserve.command
# stock.reserved
# stock.reserve.failed
# stock.release.command
# stock.released
# stock.release.failed
# courier.reserve.command
# courier.reserved
# courier.reserve.failed
# courier.release.command
# courier.released
# courier.release.failed
# user.created
```

---

## Тестирование Saga-компенсаций вручную (если нужно)

Для проверки конкретной ветки компенсации можно «сломать» один из сервисов:

**Провал warehouse:** остановить `warehouse-service` (`kubectl scale deploy/warehouse-service -n shop --replicas=0`), создать заказ. `order-service` получит `stock.reserve.command`, но `stock.reserved` не придёт. Через таймаут (если реализован) или вручную — компенсация `funds.release.command`. В текущей реализации таймаута нет: сага «зависнет» в `FUNDS_RESERVED`. Это ограничение задокументировано в [`docs/limitations.md`](limitations.md) → п. 3.

**Провал delivery:** остановить `delivery-service`, создать заказ. Сага дойдёт до `STOCK_RESERVED`, `courier.reserve.command` уйдёт в топик, но ответа не будет. Аналогично — «зависание» в `STOCK_RESERVED`.

Для полноценной проверки компенсации нужен либо таймаут в оркестраторе (не реализован), либо ручной `kafka-console-producer` с `stock.reserve.failed`/`courier.reserve.failed` для нужного `orderId`. Пример:

```bash
kubectl exec -n shop kafka-0 -- \
  /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server localhost:9092 \
  --topic stock.reserve.failed <<< \
  '{"orderId":12,"reason":"OUT_OF_STOCK"}'
```

После этого `SagaOrchestrator.onStockFailed` запустит компенсацию funds.