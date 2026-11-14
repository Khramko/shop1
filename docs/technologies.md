# 🛠 Технологии

## Backend

- **Java 17** — язык программирования (multi-module Maven, `maven.compiler.release=17`)
- **Spring Boot 3.2.5** — базовый фреймворк (`spring-boot-starter-parent`)
- **Spring Web** — REST API (`spring-boot-starter-web`)
- **Spring Data JPA** + **Hibernate** — ORM, `ddl-auto: validate`
- **Spring Security** — stateless JWT-аутентификация
- **JJWT 0.12.5** — генерация и валидация JWT (`jjwt-api`, `jjwt-impl`, `jjwt-jackson`), алгоритм HS256
- **Spring Kafka** — producer/consumer для Event Collaboration (`spring-kafka`)
- **Spring Boot Actuator** — health-checks, `/actuator/prometheus`
- **Micrometer** — метрики в формате Prometheus (`micrometer-registry-prometheus`), гистограммы `http.server.requests` (p50/p95/p99)
- **Liquibase** — версионирование схемы БД (YAML-чейнджлоги)
- **Bean Validation** (`jakarta.validation`) — валидация DTO (`@NotBlank`, `@Email`, `@Size`, `@DecimalMin`)
- **Logstash Logback Encoder 7.4** — JSON-логи с MDC (`request_id`, `http_method`, `http_path`, `remote_ip`, `http_status`, `duration_ms`)

## Хранение данных

- **PostgreSQL 16** (образ `postgres:16-alpine`)
    - Отдельная БД на сервис: `user_db`, `billing_db`, `order_db`, `notification_db`
    - Один инстанс, инициализируется через Job (`postgres-init-dbs`) или `docker/init-db.sql`
    - `pg_isready` как readiness-проба

## Брокер сообщений

- **Apache Kafka 4.1.1** (образ `apache/kafka:4.1.1`)
    - Режим **KRaft** (без ZooKeeper): `KAFKA_PROCESS_ROLES=broker,controller`
    - Single-node, `CLUSTER_ID=4L6G9nxwQreSVeTuSsh_Hg`
    - `auto.create.topics.enable=true` (топики создаются при первом publish)
    - `replication.factor=1` для offsets/transaction state (single-node)
    - Readiness-проба: `kafka-topics.sh --bootstrap-server localhost:9092 --list`

## Инфраструктура

- **Docker** — сборка образов через multi-stage `Dockerfile` с `ARG MODULE`
- **Docker Compose** — локальный запуск всего стека (`docker-compose.yml`), nginx-gateway на `:8080`
- **Kubernetes** — оркестрация (`k8s/`): `StatefulSet` для PostgreSQL и Kafka, `Deployment` для сервисов, `Job` для инициализации БД
- **nginx-ingress** — маршрутизация по хосту `arch.homework` и путям `/auth`, `/api`, `/billing`, `/orders`, `/notifications`
- **Nginx** (alpine) — gateway в Docker Compose (`docker/nginx-gateway.conf`)
- **Helm** — опционально для ingress-nginx / kube-prometheus-stack

## Общие библиотеки (модули `common/`)

| Модуль | Назначение | Ключевые классы |
|---|---|---|
| `common-events` | Контракты событий и имена топиков | `Topics`, `UserCreatedEvent`, `OrderCreatedEvent`, `FundsWithdrawnEvent`, `FundsWithdrawFailedEvent`, `OrderPaidEvent`, `OrderFailedEvent` |
| `common-security` | JWT, фильтр, SecurityConfig | `JwtService`, `JwtAuthenticationFilter`, `SecurityConfig`, `AuthenticatedUser`, `PasswordEncoder` (BCrypt) |
| `common-logging` | Единое логирование и обработка ошибок | `RequestLoggingFilter`, `GlobalExceptionHandler`, `LifecycleLogger`, `logback-spring.xml` |

## Сборка и запуск

- **Maven 3.9** (`maven:3.9-eclipse-temurin-17` в build-стадии Dockerfile)
- **`build-all.sh`** — собирает 4 образа `shop/<module>:dev`
- **`k8s/install.sh`** — последовательное применение манифестов
- **`run-newman.sh`** — E2E-прогон Postman-коллекции через Newman (`npx newman`)

## Инструменты разработки

- **IntelliJ IDEA** (проект с Maven-модулями, Checkstyle-IDEA)
- **Postman / Newman** — E2E-тесты (`etc/shop-stream-app.postman_collection.json`)
- **Mermaid** — диаграммы в документации (`docs/theory.md`, `ARCHITECTURE.md`)

## Сводная таблица версий

| Компонент | Версия |
|---|---|
| Java | 17 |
| Spring Boot | 3.2.5 |
| JJWT | 0.12.5 |
| logstash-logback-encoder | 7.4 |
| PostgreSQL | 16-alpine |
| Apache Kafka | 4.1.1 |
| Maven (build) | 3.9 |