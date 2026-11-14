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

Поднимает всё: PostgreSQL, Kafka (KRaft), 4 сервиса, nginx-gateway на `:8080`.

### 1. Собрать образы

```bash
./build-all.sh dev
```

Скрипт собирает 4 образа из единого `Dockerfile` с `ARG MODULE`:

- `shop/user-service:dev`
- `shop/billing-service:dev`
- `shop/order-service:dev`
- `shop/notification-service:dev`

Каждая сборка — multi-stage: `maven:3.9-eclipse-temurin-17` → `eclipse-temurin:17-jdk-alpine`, non-root `appuser`. Первая сборка скачивает зависимости (5–10 мин), последующие — быстрее за счёт кэша Maven.

### 2. Запустить стек

```bash
docker compose up -d
```

Порядок гарантирован `depends_on` + `healthcheck`:

1. `postgres` (healthcheck `pg_isready`) + `init-db.sql` создаёт 4 БД.
2. `kafka` (healthcheck `kafka-topics.sh --list`).
3. 4 сервиса (после healthy postgres и kafka).
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

Полноценная проверка — E2E через Newman (см. [Тестирование](#тестирование)).

### 4. Логи и остановка

```bash
docker compose logs -f order-service
docker compose logs -f kafka
docker compose down            # остановить, данные сохранить
docker compose down -v         # остановить и удалить тома pgdata/kafkadata
```

### 5. Пересборка одного сервиса

```bash
docker compose build user-service
docker compose up -d user-service
```

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

Для **kind**: `kind load docker-image shop/user-service:dev ...`
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
3. `postgres.yaml` → `rollout status statefulset/postgres` + `job/postgres-init-dbs`
4. `kafka.yaml` → `rollout status statefulset/kafka` (startup дольше из-за KRaft)
5. `user-service/`, `billing-service/`, `order-service/`, `notification-service/`
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

curl -s http://arch.homework/health
```

### 7. Удалить

```bash
cd k8s
./uninstall.sh
```

Скрипт удаляет в обратном порядке: ingress → сервисы → kafka → postgres → configmap/secrets → namespace.

---

## Проверка «end-to-end» вручную

Если не хотите Newman, минимальный сценарий через `curl`:

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

# 4. Заказ на 300
ORDER=$(curl -s -X POST "$BASE/orders" \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"price":300}')
OID=$(echo "$ORDER" | jq -r .orderId)

# 5. Polling до PAID (обычно 1–3 сек)
for i in $(seq 1 20); do
  S=$(curl -s "$BASE/orders/$OID" -H "Authorization: Bearer $TOKEN" | jq -r .status)
  [ "$S" = "PAID" ] && break
  sleep 0.5
done
echo "order status: $S"

# 6. Баланс 700 и HAPPY-уведомление
curl -s "$BASE/billing/accounts/me" -H "Authorization: Bearer $TOKEN"
curl -s "$BASE/notifications" -H "Authorization: Bearer $TOKEN"
```

Аналогично для неуспешного заказа — `price: 5000` → через 1–3 сек `FAILED`, баланс остаётся `700`, появляется `ANGRY`.

---

## Тестирование

E2E через Postman/Newman (11 запросов, 22 ассерта):

```bash
./run-newman.sh                        # default baseUrl = http://arch.homework
./run-newman.sh http://localhost:8080  # против Compose
```

Скрипт:

- проверяет наличие `npx`;
- генерирует временный env-файл с `baseUrl`;
- запускает `npx newman run etc/shop-stream-app.postman_collection.json`;
- пишет отчёт в `reports/newman-<timestamp>.json`.

Polling реализован через `postman.setNextRequest`:

| Шаг | Условие повтора | Максимум |
|---|---|---|
| `02. Poll account creation` | `404`/`500` (аккаунт ещё не создан) | 10 попыток |
| `05. Poll order1 until PAID` | `status === 'NEW'` | 20 попыток |
| `09. Poll order2 until FAILED` | `status === 'NEW'` | 20 попыток |
| `07. Poll notifications for HAPPY` | нет `HAPPY` для `order1Id` | 20 попыток |
| `11. Poll notifications for ANGRY` | нет `ANGRY` для `order2Id` | 20 попыток |

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
| Аккаунт не появляется | `user.created` потерялось / consumer упал | `kubectl logs -n shop deploy/billing-service`, проверить consumer groups |
| Newman падает на шаге 2 | аккаунт не создан за 10 попыток | увеличить `sleep`/`postman.setNextRequest`, посмотреть `billing-service` лог |
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