# Архитектура Shop Stream App

## Выбранный вариант взаимодействия
**Event Collaboration** (вариант 3 из ДЗ).

Сервисы общаются через Kafka. Каждый сервис:
- публикует доменные события о своих изменениях;
- подписывается на события других сервисов.

Нет прямых HTTP-вызовов между сервисами. Это обеспечивает слабую связность
и возможность независимого развития/масштабирования.

## Топики Kafka
| Топик | Producer | Consumers | Payload |
|---|---|---|---|
| `user.created` | user-service | billing-service, notification-service | `UserCreatedEvent` |
| `order.created` | order-service | billing-service | `OrderCreatedEvent` |
| `funds.withdrawn` | billing-service | order-service | `FundsWithdrawnEvent` |
| `funds.withdraw.failed` | billing-service | order-service | `FundsWithdrawFailedEvent` |
| `order.paid` | order-service | notification-service | `OrderPaidEvent` |
| `order.failed` | order-service | notification-service | `OrderFailedEvent` |

## Sequence-диаграмма (успешный заказ)

```mermaid
sequenceDiagram
    participant U as User
    participant O as Order Service
    participant K as Kafka
    participant B as Billing Service
    participant N as Notification Service

    U->>O: POST /orders {price: 300}
    O->>O: save Order(status=NEW)
    O->>K: publish order.created
    K->>B: order.created
    B->>B: check balance ≥ price
    B->>K: publish funds.withdrawn
    K->>O: funds.withdrawn
    O->>O: Order.status = PAID
    O->>K: publish order.paid
    K->>N: order.paid
    N->>N: save Notification(HAPPY)
    U->>O: GET /orders/{id}
    O-->>U: {status: PAID}
    U->>N: GET /notifications
    N-->>U: [{type: HAPPY, orderId: ...}]
## Идемпотентность

Kafka гарантирует at-least-once delivery, поэтому любой consumer должен быть идемпотентным.

| Сервис | Таблица | Ключ | Что защищает |
|---|---|---|---|
| billing-service | `processed_orders` | `order_id` (PK) | Повторное списание по одному заказу |
| notification-service | `user_email_projection` | `user_id` (PK) | Повторная запись email-проекции |
| order-service | — | статус-машина `NEW → PAID/FAILED` | Повторный `funds.withdrawn` не меняет терминальный статус |

## Известные ограничения

- **Нет Outbox pattern.** Публикация в Kafka и коммит транзакции в БД не атомарны: при сбое между `save()` и `kafka.send()` событие может потеряться. Для ДЗ («сервисы не падают») это допустимо.
- **Нет retry / DLQ.** Consumer при исключении ретраит бесконечно (Spring Kafka default). Для продакшена нужен `DefaultErrorHandler` + dead-letter topic.
- **Нет saga-компенсации.** Если funds.withdrawn получен, а order.paid потерян — заказ навсегда останется NEW при списанных деньгах.
- **Kafka = single-node (KRaft).** Для продакшена нужен кластер ≥3 брокеров и replication factor ≥2.
- **Postgres = single instance.** Без HA/backup.

## Файлы для проверяющего

- `docs/architecture.png` — схема взаимодействия сервисов
- `docs/theory.md` — 4 варианта sequence-диаграмм
- `docs/api.yaml` — OpenAPI (IDL)
- `reports/newman-*.json` — отчёт последнего прогона
