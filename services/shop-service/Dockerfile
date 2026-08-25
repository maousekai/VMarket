# Build tu goc repo: docker build -f services/shop-service/Dockerfile .
# ---- Build stage (multi-stage voi Maven, build dung module can thoi) ----
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build

COPY services/pom.xml ./
COPY services/shop-service/pom.xml shop-service/
COPY services/shop-service/src shop-service/src
RUN mvn -B -q -f shop-service/pom.xml clean package -DskipTests

# ---- Runtime stage ----
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

RUN addgroup -S vmarket && adduser -S vmarket -G vmarket
USER vmarket

COPY --from=build /build/shop-service/target/*.jar app.jar

EXPOSE 8083
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]