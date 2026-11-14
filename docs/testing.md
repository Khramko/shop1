# 🧪 Тестирование

Проект проверяется **E2E-сценарием через Postman/Newman**: полный путь `регистрация → deposit → успешный заказ → проверка баланса и HAPPY → неуспешный заказ → проверка баланса и ANGRY`. Юнит-тестов и integration-тестов (Testcontainers) в проекте нет — их место занимает E2E, покрывающий всю event-driven цепочку.

---

## Коллекция Postman

Файл: [`etc/shop-stream-app.postman_collection.json`](../etc/shop-stream-app.postman_collection.json)

- **11 запросов**, **22 ассерта** (по 2 на запрос).
- Переменные коллекции: `baseUrl`, `email`, `password`, `name`, `userId`, `token`, `order1Id`, `order2Id`, `accountId` + счётчики попыток `accountPollAttempt`, `order1PollAttempt`, `order2PollAttempt`, `notif1PollAttempt`, `notif2PollAttempt`.
- Окружение: [`etc/shop-local.postman_environment.json`](../etc/shop-local.postman_environment.json) — `baseUrl=http://localhost:8080`, `userUrl`, `billingUrl`, `orderUrl`, `notifUrl` (в текущей коллекции используется только `baseUrl`).

### Шаги

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

### Pre-request и test-скрипты

Pre-request `01` генерирует уникальные данные на каждый прогон — не нужна очистка БД между запусками:

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

Счётчик сбрасывается в `0` при успехе — коллекция готова к повторному прогону.

---

## Запуск через Newman

Скрипт [`run-newman.sh`](../run-newman.sh):

```bash
./run-newman.sh                        # default: http://arch.homework
./run-newman.sh http://localhost:8080  # против Docker Compose
```

Внутри:

1. Проверка `npx` (Node.js ≥18).
2. Генерация временного env-файла с `baseUrl` (не трогает `etc/shop-local.postman_environment.json`).
3. `npx newman run etc/shop-stream-app.postman_collection.json --environment <tmp> --timeout-request 30000 --delay-request 500 --reporters cli,json --reporter-json-export reports/newman-<ts>.json`.
4. Удаление временного env.

Флаги:

- `--timeout-request 30000` — 30 с на запрос (запас на холодный старт).
- `--delay-request 500` — пауза между **разными** запросами (не влияет на `postman.setNextRequest`, который выполняется сразу).
- `--reporters cli,json` — человекочитаемый вывод + машинный отчёт.

### Пример успешного вывода

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

`run.stats.assertions.failed == 0` и `run.failures == []` — критерий успеха.

---

## Что именно проверяется

### Happy path (шаги 01–07)

- **Регистрация** создаёт пользователя и выдаёт валидный JWT.
- **`user.created`** доходит до billing → аккаунт создаётся асинхронно. Polling шага 02 маскирует задержку.
- **Депозит** синхронно обновляет баланс (`200`, `1000.00`).
- **Заказ 300** возвращает `202` и `NEW`, а через Kafka-цепочку `order.created → funds.withdrawn → order.paid` переходит в `PAID`.
- **Баланс** списан ровно на 300 (`700.00`).
- **HAPPY-уведомление** создано из `order.paid`.

### Failure path (шаги 08–11)

- **Заказ 5000** при балансе 700 → `202`/`NEW`.
- **`order.created`** доходит до billing, баланс `< price` → `funds.withdraw.failed {reason: INSUFFICIENT_FUNDS}` → order-service ставит `FAILED`.
- **Баланс не изменился** (`700.00`) — ключевая проверка идемпотентности/атомарности списания.
- **ANGRY-уведомление** создано из `order.failed` и содержит причину `INSUFFICIENT_FUNDS`.

### Что НЕ проверяется (известные пробелы)

- **Идемпотентность при дублях.** Нет теста, который публикует `funds.withdrawn` дважды и проверяет, что баланс списан один раз. Логика есть (`processed_orders`, статус-машина `NEW → PAID/FAILED`), но тестом не покрыта.
- **Потеря событий (Outbox).** Нет теста на сбой между `save()` и `kafka.send()`.
- **Retry / DLQ.** Consumer при исключении ретраит бесконечно — не проверяется.
- **Сага-компенсация.** Если `funds.withdrawn` получен, а `order.paid` потерян, заказ остаётся `NEW` — не проверяется.
- **Concurrent orders.** Два заказа одного пользователя одновременно при балансе, хватающем только на один. `accounts` обновляется без оптимистичной блокировки (`@Version`), возможна гонка.
- **JWT edge-cases.** Просроченный/поддельный токен, отсутствие `Authorization` — не покрыты (проверяются `SecurityConfig` вручную).
- **Валидация.** `400` при невалидном `email`/`price` — не покрыта.

Эти пробелы задокументированы в [`ARCHITECTURE.md`](../ARCHITECTURE.md) → «Известные ограничения».

---

## Ручная проверка

Если нет Node.js/Newman, тот же сценарий — через `curl` (см. [`docs/api.md`](api.md) → «Пример: полный сценарий через `curl`»). Ключевые проверки:

```bash
BASE=http://arch.homework
# ... регистрация, deposit, заказ 300
curl -s "$BASE/orders/$OID" -H "Authorization: Bearer $TOKEN" | jq .status   # PAID
curl -s "$BASE/billing/accounts/me" -H "Authorization: Bearer $TOKEN" | jq .balance  # 700.00
curl -s "$BASE/notifications" -H "Authorization: Bearer $TOKEN" | jq '[.[] | select(.type=="HAPPY")]'
```

---

## Диагностика падений

| Симптом в Newman | Вероятная причина | Что смотреть |
|---|---|---|
| `02. Poll account creation` падает через 10 попыток | `user.created` потерялось / billing-service не подписан / consumer упал | `kubectl logs -n shop deploy/billing-service`, `kafka-consumer-groups --describe` |
| `05. Poll order1 until PAID` падает через 20 попыток | `funds.withdrawn` не дошёл до order-service, или billing не смог списать | `kubectl logs -n shop deploy/billing-service deploy/order-service` |
| `06. Check balance = 700` падает | баланс уже был не 0 (прогон без очистки БД, но email уникален — значит баг) или списание не произошло | `GET /billing/accounts/me` вручную |
| `07. Poll notifications for HAPPY` пусто | `order.paid` не дошёл до notification, или `user_email_projection` пуст (fallback `unknown@`) | `kubectl logs -n shop deploy/notification-service` |
| `09. Poll order2 until FAILED` висит на `NEW` | `order.created` потерян / billing не подписан | consumer groups, billing-логи |
| `401` на шагах 02+ | `token` не сохранился (шаг 01 упал) или просрочен | смотреть шаг 01, `exp` в JWT |
| `502` на шаге 01 | ingress не достучался до user-service | `kubectl get pods -n shop`, readiness |

Проверить consumer lag:

```bash
kubectl exec -n shop kafka-0 -- \
  /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 --describe --all-groups
```

`LAG` растёт и не уменьшается → consumer завис или упал, `CURRENT-OFFSET` не двигается.

---

## Быстрая проверка «система жива» без Newman

```bash
for s in user billing order notification; do
  curl -s "http://arch.homework/health" | jq -r .service
done
# user-service
# billing-service
# order-service
# notification-service
```

И наличие всех топиков:

```bash
kubectl exec -n shop kafka-0 -- \
  /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list
# order.created
# order.failed
# order.paid
# funds.withdraw.failed
# funds.withdrawn
# user.created
```