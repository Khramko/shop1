# shop/Dockerfile
FROM maven:3.9-eclipse-temurin-17 AS build
ARG MODULE
WORKDIR /src
COPY pom.xml .
COPY common ./common
COPY user-service ./user-service
COPY billing-service ./billing-service
COPY order-service ./order-service
COPY notification-service ./notification-service
RUN mvn -q -pl ${MODULE} -am -DskipTests package

FROM eclipse-temurin:17-jdk-alpine
ARG MODULE
WORKDIR /app
COPY --from=build /src/${MODULE}/target/*.jar app.jar
RUN addgroup -S appgroup && adduser -S appuser -G appgroup && chown appuser:appgroup app.jar
USER appuser
EXPOSE 8081 8082 8083 8084
ENTRYPOINT ["java", "-jar", "app.jar"]