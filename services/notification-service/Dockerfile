# Build tu goc repo: docker build -f services/notification-service/Dockerfile .
# ---- Build stage (multi-stage voi Maven, build dung module can thoi) ----
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build

COPY services/pom.xml ./
COPY services/notification-service/pom.xml notification-service/
COPY services/notification-service/src notification-service/src
RUN mvn -B -q -f notification-service/pom.xml clean package -DskipTests

# ---- Runtime stage ----
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

RUN addgroup -S vmarket && adduser -S vmarket -G vmarket
USER vmarket

COPY --from=build /build/notification-service/target/*.jar app.jar

EXPOSE 8090
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]