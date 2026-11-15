# Паттерны, использованные в проекте

## ДЗ №1 — Распределённые транзакции

**Паттерн: Orchestration Saga** (централизованный оркестратор + хореография шагов через Kafka).

### Почему Orchestration, а не Choreography

- В сценарии три участника (billing → warehouse → delivery) и жёсткий порядок шагов.
- Нужна явная точка принятия решения о компенсации — ею стал `SagaOrchestrator` в `order-service`.
- Состояние саги хранится в `saga_states` (order_id PK, step, failure_reason, updated_at) — это позволяет:
  - переживать рестарт order-service (шаг восстанавливается из БД);
  - отлаживать конкретный заказ по `order_id`;
  - строить компенсацию строго по тому, что успело зарезервироваться.

### Поток событий (Kafka topics)

| Шаг | Топик | Producer → Consumer |
|---|---|---|
| Старт | `order.saga.started` | order → (audit) |
| Списание | `funds.reserve.command` | order → billing |
| Списание OK | `funds.reserved` | billing → order |
| Списание FAIL | `funds.reserve.failed` | billing → order |
| Резерв товара | `stock.reserve.command` | order → warehouse |
| Товар OK | `stock.reserved` | warehouse → order |
| Товар FAIL | `stock.reserve.failed` | warehouse → order |
| Резерв курьера | `courier.reserve.command` | order → delivery |
| Курьер OK | `courier.reserved` | delivery → order |
| Курьер FAIL | `courier.reserve.failed` | delivery → order |
| Успех | `order.paid` | order → notification |
| Компенсация: товар | `stock.release.command` | order → warehouse |
| Товар освобождён | `stock.released` | warehouse → order |
| Компенсация: деньги | `funds.release.command` | order → billing |
| Деньги возвращены | `funds.released` | billing → order |
| Провал | `order.failed` | order → notification |

Полный список топиков и payload — в [`docs/kafka-topics.md`](kafka-topics.md).

### Компенсации (обратный порядок)

| Где провалились | `doneStep` | Что откатываем |
|---|---|---|
| billing (`STARTED`) | — | ничего — деньги не списаны |
| warehouse (`FUNDS_RESERVED`) | `FUNDS_RESERVED` | `funds.release.command` |
| delivery (`STOCK_RESERVED`) | `STOCK_RESERVED` | `stock.release.command` → после `stock.released` цепочкой `funds.release.command` |

Курьер **не освобождается** при провале delivery — по контракту `DeliveryService.reserve` бросает исключение до сохранения `Reservation`, т.е. резерва не было. Ограничение (если `reserve` упал после `save`) — см. [`docs/limitations.md`](limitations.md) → п. 15.

### Идемпотентность участников

| Сервис | Таблица-маркер |
|---|---|
| billing | `reserved_funds` (order_id PK) |
| warehouse | `reservations` (order_id PK) |
| delivery | `reservations` (order_id PK) |

Повторная доставка команды не приводит к двойному списанию/резерву.

### Таймаут шага саги (не реализован)

`SagaOrchestrator` реагирует на события, но не имеет таймера. Если ответ от billing/warehouse/delivery не пришёл (сервис упал, событие потеряно), сага «зависает»:

- `STARTED` — после `funds.reserve.command` без ответа;
- `FUNDS_RESERVED` — после `stock.reserve.command` без ответа;
- `STOCK_RESERVED` — после `courier.reserve.command` без ответа.

**Последствия:** деньги/товар/курьер зарезервированы, но заказ не завершён; нет автоматической компенсации.

**Решение (для продакшена):** `@Scheduled` каждые N секунд ищет `SagaState` со `step IN (STARTED, FUNDS_RESERVED, STOCK_RESERVED)` и `updated_at < now - timeout`, публикует `*.failed` с `reason=TIMEOUT` → запускает компенсацию. Плюс метрика `saga_stuck_total{step}`.

См. [`docs/limitations.md`](limitations.md) → п. 3.

### Управление состоянием саги

Статус заказа (`Order.status`) и шаг саги (`SagaState.step`) — **разные** сущности:

| `Order.status` | `SagaState.step` | Что значит |
|---|---|---|
| `RESERVING` | `STARTED` | сага стартовала, funds ещё не подтверждены |
| `RESERVING` | `FUNDS_RESERVED` | funds зарезервированы, stock в процессе |
| `RESERVING` | `STOCK_RESERVED` | stock зарезервирован, courier в процессе |
| `PAID` | `DONE` | все шаги успешны |
| `COMPENSATING` | `COMPENSATING` | идёт откат |
| `FAILED` | `FAILED` | компенсация завершена |
| `COMPENSATION_FAILED` | `COMPENSATION_FAILED` | компенсация не удалась |

Разделение позволяет UI показывать грубый статус (`RESERVING`), а отладке — точный шаг (`FUNDS_RESERVED`).

---

## ДЗ №2 — Идемпотентность API создания заказа

**Паттерн: Idempotency-Key** (клиентский ключ + персистентный журнал ответов).

### Схема

1. Клиент передаёт `Idempotency-Key: <uuid>` в заголовке `POST /orders`.
2. `OrderService.createIdempotent()` ищет пару `(user_id, idempotency_key)` в таблице `idempotency_keys`.
3. **Если найдена** — возвращает сохранённые `http_status` и `response_body` с заголовком `X-Idempotent-Replay: true`. Новый заказ **не создаётся**.
4. **Если не найдена** — создаёт заказ (через `createSaga`), сериализует ответ в JSON и сохраняет в `idempotency_keys`.
5. **Гонка** (два параллельных запроса с одним ключом) — ловится `DataIntegrityViolationException` по `uq_idempotency_user_key`, затем читается уже сохранённая запись и возвращается как replay.

### Ключевые элементы

- Уникальное ограничение `(user_id, idempotency_key)` — гарантия на уровне БД.
- Хранение полного тела ответа (`response_body TEXT`, `http_status INT`) — replay возвращает **байт-в-байт** тот же JSON.
- TTL 24 часа — `IdempotencyKeyCleanupJob` (`@Scheduled(cron = "0 0 * * * *")`) удаляет старые ключи.
- Обратная совместимость: если `Idempotency-Key` не передан — каждый вызов создаёт новый заказ (поведение v1).

### Почему не «дедупликация по телу запроса»

- Тело может содержать `deliverySlot`, который клиент передаёт в разном формате (`2026-11-16T10:00` vs `2026-11-16T10:00:00`).
- Хеш тела не отличает «клиент повторил запрос» от «клиент создал два одинаковых заказа намеренно».
- `Idempotency-Key` — это явный контракт: «я повторяю, верни тот же ответ».

### Почему не «идемпотентность на уровне БД» (unique constraint на `orders`)

- У заказа нет естественного бизнес-ключа (кроме `id`, который генерируется).
- Два одинаковых заказа — легитимный сценарий (клиент хочет два раза по 300).
- `Idempotency-Key` отделяет «повтор» от «нового заказа».

### Что НЕ является идемпотентным

- `POST /billing/accounts/me/deposit` — пополнение намеренно не идемпотентно (каждое нажатие = новое пополнение). При необходимости можно навесить такой же `Idempotency-Key`.
- `GET`-эндпоинты идемпотентны по HTTP-семантике.

### Идемпотентность consumer'ов (Kafka at-least-once)

Kafka даёт **at-least-once**: одно сообщение может быть доставлено повторно (ребаланс, рестарт, retry). Каждый consumer защищён:

| Сервис | Топик | Защита |
|---|---|---|
| billing | `user.created` | `existsByUserId` |
| billing | `funds.reserve.command` | `reserved_funds.existsById(orderId)` |
| billing | `funds.release.command` | если `reserved_funds` нет — публикует `funds.released` (идемпотентный ответ) |
| warehouse | `stock.reserve.command` | `reservations.existsById(orderId)` |
| warehouse | `stock.release.command` | проверка `status == RELEASED` |
| delivery | `courier.reserve.command` | `reservations.existsById(orderId)` |
| delivery | `courier.release.command` | проверка `status == RELEASED` |
| order (v1) | `funds.withdrawn` | `if (status != NEW) skip` |
| order (saga) | все saga-события | проверка `SagaState.step` — ожидаемый шаг, иначе пропуск |

### Коммутативность

Для saga-команд порядок **не** коммутативен: `funds.reserve.command` должен быть обработан до `stock.reserve.command` (иначе stock зарезервируется без денег). Это обеспечивается:

- ключом `orderId` — все команды одного заказа идут в одну партицию;
- проверкой `SagaState.step` — `onStockReserved` игнорируется, если `step != FUNDS_RESERVED`.

Для v1-событий (`funds.withdrawn`, `funds.withdraw.failed`) порядок тоже важен, но защищён статус-машиной `NEW → PAID/FAILED`.

---

## Ссылки

- [`docs/limitations.md`](limitations.md) — известные ограничения (в т.ч. п. 3 — таймаут саги, п. 15 — компенсация delivery)
- [`docs/kafka-topics.md`](kafka-topics.md) — полный список топиков и payload
- [`docs/theory.md`](theory.md) — сравнение вариантов взаимодействия (HTTP, Event Collaboration, Saga)
- [`docs/api.md`](api.md) — HTTP API с блоком «Идемпотентность»
- [`docs/testing.md`](testing.md) — Postman/Newman, шаги E01–E03