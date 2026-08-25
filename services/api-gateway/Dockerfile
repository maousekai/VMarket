# Build tu goc repo: docker build -f services/api-gateway/Dockerfile .
# ---- Build stage (multi-stage voi Maven, build dung module can thoi) ----
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build

COPY services/pom.xml ./
COPY services/api-gateway/pom.xml api-gateway/
COPY services/api-gateway/src api-gateway/src
RUN mvn -B -q -f api-gateway/pom.xml clean package -DskipTests

# ---- Runtime stage ----
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

RUN addgroup -S vmarket && adduser -S vmarket -G vmarket
USER vmarket

COPY --from=build /build/api-gateway/target/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]