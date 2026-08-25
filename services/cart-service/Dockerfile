# Build tu goc repo: docker build -f services/cart-service/Dockerfile .
# ---- Build stage (multi-stage voi Maven, build dung module can thoi) ----
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build

COPY services/pom.xml ./
COPY services/cart-service/pom.xml cart-service/
COPY services/cart-service/src cart-service/src
RUN mvn -B -q -f cart-service/pom.xml clean package -DskipTests

# ---- Runtime stage ----
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

RUN addgroup -S vmarket && adduser -S vmarket -G vmarket
USER vmarket

COPY --from=build /build/cart-service/target/*.jar app.jar

EXPOSE 8085
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]