# ============================================================
# Picsou — All-in-one Docker image (frontend + backend)
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
FROM eclipse-temurin:21-jre-jammy

# Install Nginx + supervisor + openssl (entrypoint auto-generates secrets on first boot)
RUN apt-get update && apt-get install -y --no-install-recommends \
        nginx \
        supervisor \
        openssl \
    && apt-get clean \
    && rm -rf /var/lib/apt/lists/*

ARG APP_VERSION=dev
ENV APP_VERSION=$APP_VERSION

# Copy built frontend
COPY --from=frontend-build /build/frontend/dist /usr/share/nginx/html

# Copy built backend
COPY --from=backend-build /build/backend/target/*.jar /app/app.jar

# Copy configs
COPY docker/nginx.conf /etc/nginx/sites-available/picsou
RUN rm -f /etc/nginx/sites-enabled/default \
    && ln -s /etc/nginx/sites-available/picsou /etc/nginx/sites-enabled/picsou \
    # nginx.conf includes this snippet unconditionally; entrypoint.sh rewrites
    # it on every boot from HSTS_ENABLED. Created empty here so the config is
    # valid even if nginx is exercised without the entrypoint (e.g. nginx -t).
    && mkdir -p /etc/nginx/snippets \
    && : > /etc/nginx/snippets/picsou-hsts.conf \
    # Fail the build on a broken nginx config rather than at container start.
    # Needs the docroot to exist, and validates the HSTS-disabled shape (the
    # enabled one is exercised by the entrypoint at boot).
    && mkdir -p /usr/share/nginx/html \
    && nginx -t

COPY docker/supervisord.conf /etc/supervisor/conf.d/picsou.conf
COPY docker/entrypoint.sh /app/entrypoint.sh
RUN chmod +x /app/entrypoint.sh

EXPOSE 8080

ENTRYPOINT ["/app/entrypoint.sh"]
