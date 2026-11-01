# ─── Stage 1: Build ────────────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-26-alpine AS build

WORKDIR /app

# Cache dependencies first (layer cache optimization)
COPY pom.xml .
RUN mvn dependency:go-offline -q

# Build application
COPY src ./src
RUN mvn package -DskipTests -q

# ─── Stage 2: Runtime ──────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# Non-root user for security
RUN addgroup -S picsou && adduser -S picsou -G picsou
USER picsou

COPY --from=build /app/target/*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=75.0", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-jar", "app.jar"]
