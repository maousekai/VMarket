# ---- Build stage (multi-stage voi Maven) ----
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app

# Cache dependency truoc khi copy source
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B -q clean package -DskipTests

# ---- Runtime stage ----
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

RUN addgroup -S vmarket && adduser -S vmarket -G vmarket
USER vmarket

COPY --from=build /app/target/*.jar app.jar

EXPOSE 8082
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
