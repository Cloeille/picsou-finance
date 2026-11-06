# ============================================================
# Picsou — Docker image (frontend + backend, one Java process)
# Build context: project root (..)
# ============================================================

# ---------- Stage 1: Build frontend ----------
# Static assets are the same on every architecture, so both build stages run
# natively on the builder instead of once per target platform under emulation.
FROM --platform=$BUILDPLATFORM oven/bun:alpine AS frontend-build

WORKDIR /build/frontend

COPY frontend/package.json frontend/bun.lock ./
RUN bun install --frozen-lockfile

# Declared after the install: a new version must not invalidate the dependency layer.
ARG VITE_DEMO_MODE=false
ARG APP_VERSION=dev
ENV VITE_DEMO_MODE=$VITE_DEMO_MODE
ENV VITE_APP_VERSION=$APP_VERSION

COPY frontend/ .
RUN bun run build

# ---------- Stage 2: Build backend ----------
FROM --platform=$BUILDPLATFORM maven:3.9-eclipse-temurin-21-alpine AS backend-build

WORKDIR /build/backend

COPY backend/pom.xml .
RUN mvn dependency:go-offline -q

COPY backend/src ./src
RUN mvn package -DskipTests -q

# ---------- Stage 3: Runtime ----------
# One non-root Java process: Spring Boot serves the SPA and the API on 8080 and is PID 1.
FROM eclipse-temurin:21-jre-jammy

RUN groupadd --gid 1000 picsou \
    && useradd --uid 1000 --gid 1000 --home-dir /app --no-create-home --shell /usr/sbin/nologin picsou \
    && mkdir -p /data \
    && chown picsou:picsou /data

ARG APP_VERSION=dev
ENV APP_VERSION=$APP_VERSION

# Copy built frontend; Spring Boot serves it from SPA_LOCATION
COPY --from=frontend-build /build/frontend/dist /app/spa
ENV SPA_LOCATION=/app/spa

# The app generates and persists its secrets here on first start (SecretBootstrap).
ENV PICSOU_SECRETS_DIR=/data/.secrets

# Sync at startup stays off unless the operator turns it on.
ENV APP_STARTUP_SYNC_ENABLED=false

# Copy built backend
COPY --from=backend-build /build/backend/target/*.jar /app/app.jar

WORKDIR /app
USER 1000:1000
EXPOSE 8080

ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-Djava.security.egd=file:/dev/./urandom", "-jar", "/app/app.jar"]
