# 🚀 Быстрый старт

Проект можно поднять двумя способами: **Docker Compose** (локально, без Kubernetes) и **Kubernetes** (k8s-манифесты в `k8s/`). В обоих случаях приложение доступно по `http://arch.homework` через nginx-ingress/gateway.

## Требования

| Инструмент | Версия | Для чего |
|---|---|---|
| Docker | 24+ | сборка образов, Compose |
| Docker Compose | v2 | локальный запуск |
| Maven | 3.9 | сборка (если не используете Docker build) |
| Java | 17 | локальная разработка |
| kubectl | 1.27+ | только для k8s |
| kind / minikube / Docker Desktop k8s | — | только для k8s |
| nginx-ingress | — | только для k8s |
| Node.js | 18+ | `run-newman.sh` |
| Newman | — | через `npx`, ставить отдельно не нужно |

Проверка:

```bash
docker --version
docker compose version
mvn --version          # опционально
kubectl version --client
node --version
```

---

## Вариант А: Docker Compose (локально)

Поднимает всё: PostgreSQL, Kafka (KRaft), **6 сервисов**, nginx-gateway на `:8080`.

### 1. Собрать образы

```bash
./build-all.sh dev
```

Скрипт собирает **6 образов** из единого `Dockerfile` с `ARG MODULE`:

- `shop/user-service:dev`
- `shop/billing-service:dev`
- `shop/order-service:dev`
- `shop/notification-service:dev`
- `shop/warehouse-service:dev`
- `shop/delivery-service:dev`

Каждая сборка — multi-stage: `maven:3.9-eclipse-temurin-17` → `eclipse-temurin:17-jdk-alpine`, non-root `appuser`. Первая сборка скачивает зависимости (5–10 мин), последующие — быстрее за счёт кэша Maven.

### 2. Запустить стек

```bash
docker compose up -d
```

Порядок гарантирован `depends_on` + `healthcheck`:

1. `postgres` (healthcheck `pg_isready`) + `init-db.sql` создаёт **6 БД** (`user_db`, `billing_db`, `order_db`, `notification_db`, `warehouse_db`, `delivery_db`).
2. `kafka` (healthcheck `kafka-topics.sh --list`).
3. 6 сервисов (после healthy postgres и kafka).
4. `gateway` (nginx) на `:8080`.

### 3. Проверить

```bash
docker compose ps
# все сервисы healthy/running

curl -s http://localhost:8080/health
# {"status":"OK","service":"user-service"} — зависит от того, куда попадёт по location
```

Gateway маршрутизирует по путям (`docker/nginx-gateway.conf`):

| Путь | Сервис |
|---|---|
| `/auth/*`, `/api/*` | `user-service:8081` |
| `/billing/*` | `billing-service:8082` |
| `/orders*` | `order-service:8083` |
| `/notifications*` | `notification-service:8084` |
| `/warehouse/*` | `warehouse-service:8085` |
| `/delivery/*` | `delivery-service:8086` |

Полноценная проверка — E2E через Newman (см. [Тестирование](#тестирование)).

### 4. Логи и остановка

```bash
docker compose logs -f order-service
docker compose logs -f warehouse-service
docker compose logs -f delivery-service
docker compose logs -f kafka
docker compose down            # остановить, данные сохранить
docker compose down -v         # остановить и удалить тома pgdata/kafkadata
```

### 5. Пересборка одного сервиса

```bash
docker compose build order-service
docker compose up -d order-service
```

### 6. Проверка `warehouse-service` / `delivery-service` отдельно

```bash
# stock: 10 SKU по 100 шт (seed 003-seed-stock.yaml)
curl -s http://localhost:8080/warehouse/stock | jq
# [{"sku":"SKU-001","qtyAvailable":100,"qtyReserved":0}, ...]

# courier slots: 30 дней × 4 слота × 3 курьера (seed 003-seed-slots.yaml)
curl -s http://localhost:8080/delivery/slots | jq 'length'
# 360
```

> `/warehouse/stock` и `/delivery/slots` не требуют JWT — это read-only справочники. В `SecurityConfig` они могут быть закрыты `authenticated()`, тогда нужен `Authorization: Bearer $TOKEN`.

---

## Вариант Б: Kubernetes

### 1. Подготовить кластер

Docker Desktop: **Settings → Kubernetes → Enable Kubernetes → Apply & Restart**.

```bash
kubectl cluster-info
kubectl get nodes
```

### 2. Установить nginx-ingress

```bash
helm repo add ingress-nginx https://kubernetes.github.io/ingress-nginx
helm repo update

helm install nginx-ingress ingress-nginx/ingress-nginx \
  -n ingress-nginx \
  --create-namespace
```

Проверка:

```bash
kubectl get pods -n ingress-nginx
kubectl get svc -n ingress-nginx
```

### 3. Собрать образы

Для **kind**: `kind load docker-image shop/user-service:dev shop/billing-service:dev ...`
Для **minikube**: `eval $(minikube docker-env) && ./build-all.sh dev`
Для **Docker Desktop**: образы уже доступны кластеру.

Иначе — `./release.sh <tag>` (шаблон для push в registry, см. `release.sh`).

### 4. Развернуть

```bash
cd k8s
./install.sh
```

`install.sh` последовательно применяет и ждёт готовности:

1. `namespace.yaml`
2. `common-configmap.yaml`, `common-secrets.yaml`
3. `postgres.yaml` → `rollout status statefulset/postgres` + `job/postgres-init-dbs` (создаёт **6 БД**)
4. `kafka.yaml` → `rollout status statefulset/kafka` (startup дольше из-за KRaft)
5. `user-service/`, `billing-service/`, `order-service/`, `notification-service/`, **`warehouse-service/`**, **`delivery-service/`**
6. `ingress.yaml`

В конце печатает `kubectl get pods -n shop` и `kubectl get ingress -n shop`.

### 5. Настроить hosts

Добавьте в `/etc/hosts` (Linux/macOS) или `C:\Windows\System32\drivers\etc\hosts` (Windows):

```
127.0.0.1 arch.homework
```

Если nginx-ingress висит на NodePort:

```bash
NODE_PORT=$(kubectl get svc -n ingress-nginx nginx-ingress-ingress-nginx-controller \
  -o jsonpath="{.spec.ports[0].nodePort}")
echo "порт: $NODE_PORT"
```

Тогда обращайтесь к `http://arch.homework:$NODE_PORT/...`.

Альтернатива без hosts — port-forward:

```bash
kubectl port-forward -n ingress-nginx svc/nginx-ingress-ingress-nginx-controller 8080:80
# затем http://localhost:8080/...
```

### 6. Проверить

```bash
kubectl get pods -n shop
kubectl get ingress -n shop
kubectl logs -n shop deploy/order-service --tail=50
kubectl logs -n shop deploy/warehouse-service --tail=50
kubectl logs -n shop deploy/delivery-service --tail=50

curl -s http://arch.homework/health
```

Ожидаемо: **9 подов** — `postgres-0`, `kafka-0`, 6 сервисов по 1 реплике (плюс `postgres-init-dbs` Job, который завершается).

### 7. Удалить

```bash
cd k8s
./uninstall.sh
```

Скрипт удаляет в обратном порядке: ingress → сервисы → kafka → postgres → configmap/secrets → namespace.

---

## Проверка «end-to-end» вручную

Если не хотите Newman, минимальный сценарий через `curl`. **Внимание:** при `saga.enabled=true` (по умолчанию) `POST /orders` требует `sku`, `qty`, `deliverySlot`.

```bash
BASE=http://arch.homework          # или http://localhost:8080 для compose

# 1. Регистрация
REG=$(curl -s -X POST "$BASE/auth/register" \
  -H 'Content-Type: application/json' \
  -d '{"name":"Test","email":"test_'$RANDOM'@example.com","password":"password123"}')
echo "$REG"
TOKEN=$(echo "$REG" | jq -r .token)

# 2. Дождаться аккаунта (billing-service создаёт его из user.created)
sleep 2
curl -s "$BASE/billing/accounts/me" -H "Authorization: Bearer $TOKEN"
# {"accountId":N,"userId":N,"balance":0.00}

# 3. Депозит 1000
curl -s -X POST "$BASE/billing/accounts/me/deposit" \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"amount":1000}'

# 4. Заказ на 300 (saga: funds → stock → courier)
SLOT=$(date -u -d "+1 day" +%Y-%m-%dT10:00 2>/dev/null || date -u -v+1d +%Y-%m-%dT10:00)
ORDER=$(curl -s -X POST "$BASE/orders" \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"sku":"SKU-001","qty":2,"deliverySlot":"'$SLOT'","price":300}')
OID=$(echo "$ORDER" | jq -r .orderId)
echo "order $OID: $(echo "$ORDER" | jq -r .status)"   # RESERVING

# 5. Polling до PAID (обычно 1–3 сек)
for i in $(seq 1 20); do
  S=$(curl -s "$BASE/orders/$OID" -H "Authorization: Bearer $TOKEN" | jq -r .status)
  [ "$S" = "PAID" ] && break
  sleep 0.5
done
echo "order status: $S"        # PAID

# 6. Баланс 700 и HAPPY-уведомление
curl -s "$BASE/billing/accounts/me" -H "Authorization: Bearer $TOKEN" | jq .balance   # 700.00
curl -s "$BASE/notifications" -H "Authorization: Bearer $TOKEN" | jq '[.[] | select(.type=="HAPPY")]'

# 7. Stock уменьшился (SKU-001: 100 → 98)
curl -s "$BASE/warehouse/stock" -H "Authorization: Bearer $TOKEN" | jq '.[] | select(.sku=="SKU-001")'
# {"sku":"SKU-001","qtyAvailable":98,"qtyReserved":2}

# 8. Слот зарезервирован (status=RESERVED)
curl -s "$BASE/delivery/slots" -H "Authorization: Bearer $TOKEN" | jq --arg s "$SLOT" '.[] | select(.slot==$s)'
```

Аналогично для неуспешного заказа — `price: 5000` → через 1–3 сек `FAILED`, баланс остаётся `700`, появляется `ANGRY`.

**Проверка компенсации (провал warehouse):**

```bash
# qty=9999 при qtyAvailable=100 → OUT_OF_STOCK
BAD=$(curl -s -X POST "$BASE/orders" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"sku":"SKU-003","qty":9999,"deliverySlot":"'$SLOT'","price":200}' | jq -r .orderId)
for i in $(seq 1 20); do
  S=$(curl -s "$BASE/orders/$BAD" -H "Authorization: Bearer $TOKEN" | jq -r .status)
  [ "$S" = "FAILED" ] && break
  sleep 0.5
done
echo "order $BAD: $S"      # FAILED

# Баланс восстановлен, stock не изменился
curl -s "$BASE/billing/accounts/me" -H "Authorization: Bearer $TOKEN" | jq .balance       # 700.00
curl -s "$BASE/warehouse/stock" -H "Authorization: Bearer $TOKEN" | jq '.[] | select(.sku=="SKU-003")'
# {"sku":"SKU-003","qtyAvailable":100,"qtyReserved":0}
```

**Проверка идемпотентности:**

```bash
IDEM="manual-$(date +%s)"
# первый вызов
curl -s -X POST "$BASE/orders" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $IDEM" \
  -d '{"sku":"SKU-001","qty":1,"deliverySlot":"'$SLOT'","price":100}' | jq .orderId

# повтор с тем же ключом → тот же orderId + X-Idempotent-Replay: true
curl -s -X POST "$BASE/orders" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $IDEM" \
  -d '{"sku":"SKU-001","qty":1,"deliverySlot":"'$SLOT'","price":100}' \
  -i | grep -i X-Idempotent-Replay     # → X-Idempotent-Replay: true
```

---

## Тестирование

E2E через Postman/Newman. Две коллекции:

| Коллекция | Что проверяет | Запросов | Ассертов |
|---|---|---|---|
| `etc/shop-stream-app.postman_collection.json` | Event Collaboration (v1) | 11 | 22 |
| `etc/shop-saga.postman_collection.json` | Saga + идемпотентность | 20 | 34 |

Запуск:

```bash
./run-newman.sh                          # stream-app, baseUrl=http://arch.homework
./run-newman.sh http://localhost:8080    # stream-app против Compose
./run-newman.sh --saga                   # saga, baseUrl=http://arch.homework
./run-newman.sh --saga http://localhost:8080
./run-newman.sh --both                   # обе подряд
```

Скрипт:

- проверяет наличие `npx`;
- генерирует временный env-файл с `baseUrl`;
- запускает `npx newman run` с `--timeout-request 30000 --delay-request 500`;
- пишет отчёт в `reports/newman-<label>-<timestamp>.json`.

Polling реализован через `postman.setNextRequest`:

| Шаг | Условие повтора | Максимум |
|---|---|---|
| `02. Poll account creation` | `404`/`500` | 10 попыток |
| `05. Poll order1 until PAID` | `status === 'NEW'` | 20 попыток |
| `09. Poll order2 until FAILED` | `status === 'NEW'` | 20 попыток |
| `07. Poll notifications for HAPPY` | нет `HAPPY` | 20 попыток |
| `11. Poll notifications for ANGRY` | нет `ANGRY` | 20 попыток |
| `A05. Poll orderA until PAID` | `RESERVING`/`NEW` | 30 попыток |
| `B02. Poll orderB until FAILED` | `RESERVING`/`COMPENSATING` | 30 попыток |
| `C02. Poll orderC until FAILED` | `RESERVING`/`COMPENSATING` | 30 попыток |

Между попытками — `--delay-request 500` мс.

---

## Типичные проблемы

| Симптом | Причина | Решение |
|---|---|---|
| `docker compose up` падает на kafka healthcheck | Kafka KRaft стартует 30–60 с | дождаться или `docker compose logs kafka` |
| `502 Bad Gateway` от ingress | сервисы ещё не ready | `kubectl get pods -n shop`, `kubectl rollout status deploy/...` |
| `curl: (6) Could not resolve host: arch.homework` | нет записи в `/etc/hosts` | добавить `127.0.0.1 arch.homework` |
| `401 Unauthorized` в `/billing/...` | не передан `Authorization` | `-H "Authorization: Bearer $TOKEN"` |
| `403` на `GET /orders/{id}` | заказ принадлежит другому пользователю | проверять своим токеном |
| `400 validation: sku, qty, deliverySlot are required` | `POST /orders` без saga-полей при `saga.enabled=true` | добавить `sku`, `qty`, `deliverySlot` |
| Аккаунт не появляется | `user.created` потерялось / consumer упал | `kubectl logs -n shop deploy/billing-service`, проверить consumer groups |
| Заказ «висит» в `RESERVING` | `stock.reserved`/`courier.reserved` не дошли | `kubectl logs -n shop deploy/warehouse-service deploy/delivery-service` |
| Заказ «висит» в `FUNDS_RESERVED` | `stock.reserve.failed`/`courier.reserve.failed` не дошли | consumer groups, логи warehouse/delivery |
| `warehouse-service` не стартует | `003-seed-stock.yaml` упал | `kubectl logs -n shop deploy/warehouse-service`, проверить Liquibase |
| `delivery-service` не стартует | `003-seed-slots.yaml` упал (PL/pgSQL `DO $$`) | `kubectl logs -n shop deploy/delivery-service` |
| Newman падает на шаге 2 | аккаунт не создан за 10 попыток | увеличить `sleep`/`postman.setNextRequest`, посмотреть `billing-service` лог |
| Newman saga падает на `A05` | сага застряла на stock/courier | `saga_states` в БД, логи warehouse/delivery |
| `UnknownHostException: postgres` локально | запуск сервиса без Docker | поднять postgres/kafka (Compose) или задать `DB_HOST=localhost` |

---

## Переменные окружения

| Переменная | Значение по умолчанию (dev) | Значение в k8s/compose | Откуда |
|---|---|---|---|
| `DB_HOST` | `localhost` | `postgres` | `shop-common` ConfigMap |
| `DB_PORT` | `5432` | `5432` | `shop-common` |
| `DB_USER` | `app_user` | `app_user` | `shop-common` |
| `DB_PASSWORD` | `securePassword123` | `securePassword123` | `shop-secrets` Secret |
| `DB_NAME` | `<service>_db` | `<service>_db` | deployment.yaml (per-service) |
| `KAFKA_BOOTSTRAP` | `localhost:9092` | `kafka:9092` | `shop-common` |
| `JWT_SECRET_KEY` | `change-me-please-...` | `prod-secret-key-...` (≥32 символа) | `shop-secrets` |
| `SPRING_PROFILES_ACTIVE` | — | `docker` | deployment.yaml / compose |
| `SAGA_ENABLED` | `true` | `true` | `application.yaml` order-service |

---

## Полезные команды

### Проверить, что все 6 сервисов живы

```bash
for path in /health; do
  for svc in user billing order notification warehouse delivery; do
    echo -n "$svc: "
    curl -s "http://arch.homework/health" | jq -r .service
    break
  done
done
```

Точнее — по каждому сервису через ingress не получится (все на `/health`), используйте `kubectl port-forward`:

```bash
for p in 8081 8082 8083 8084 8085 8086; do
  echo -n "$p: "
  curl -s "http://localhost:$p/health" | jq -r .service
done
```

### Проверить все топики

```bash
kubectl exec -n shop kafka-0 -- \
  /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list | sort
```

Ожидаемо: `user.created`, `order.created`, `order.paid`, `order.failed`, `funds.withdrawn`, `funds.withdraw.failed` + 18 saga-топиков.

### Проверить состояние саги конкретного заказа

```bash
kubectl exec -n shop postgres-0 -- \
  psql -U app_user -d order_db -c \
  "SELECT order_id, step, failure_reason, updated_at FROM saga_states ORDER BY updated_at DESC LIMIT 10;"
```

### Проверить баланс и резервы

```bash
kubectl exec -n shop postgres-0 -- \
  psql -U app_user -d billing_db -c \
  "SELECT user_id, balance FROM accounts ORDER BY id DESC LIMIT 5;"

kubectl exec -n shop postgres-0 -- \
  psql -U app_user -d billing_db -c \
  "SELECT order_id, user_id, amount FROM reserved_funds ORDER BY created_at DESC LIMIT 5;"
```

### Проверить склад

```bash
kubectl exec -n shop postgres-0 -- \
  psql -U app_user -d warehouse_db -c \
  "SELECT sku, qty_available, qty_reserved FROM stock ORDER BY sku;"
```

### Проверить курьеров

```bash
kubectl exec -n shop postgres-0 -- \
  psql -U app_user -d delivery_db -c \
  "SELECT courier_id, slot, status FROM courier_slots WHERE slot LIKE '$(date -u +%Y-%m-%d)%' ORDER BY courier_id, slot;"
```