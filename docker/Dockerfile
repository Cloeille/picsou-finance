# ============================================================
# Picsou — Docker image (frontend + Rust backend, one process)
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
# Cross-compiled on the builder for the target architecture: rustc under emulation
# would take most of an hour. The runtime stage's Debian release, so glibc matches.
FROM --platform=$BUILDPLATFORM rust:1.99-bookworm AS backend-build

ARG TARGETARCH
SHELL ["/bin/bash", "-o", "pipefail", "-c"]
# The cross toolchain is the one of the pinned image's Debian release: no version to pin apart.
# hadolint ignore=DL3008
RUN case "$TARGETARCH" in \
      amd64) triple=x86_64-unknown-linux-gnu; gnu=x86_64-linux-gnu ;; \
      arm64) triple=aarch64-unknown-linux-gnu; gnu=aarch64-linux-gnu ;; \
      *) echo "unsupported architecture: $TARGETARCH" >&2; exit 1 ;; \
    esac \
    && if [ "$(dpkg --print-architecture)" != "$TARGETARCH" ]; then \
      apt-get update \
      && apt-get install -y --no-install-recommends "gcc-${gnu//_/-}" "libc6-dev-$TARGETARCH-cross" \
      && rm -rf /var/lib/apt/lists/*; \
    fi \
    && rustup target add "$triple" \
    && printf 'export CARGO_BUILD_TARGET=%s CARGO_TARGET_%s_LINKER=%s-gcc CC_%s=%s-gcc\n' \
      "$triple" "$(tr '[:lower:]-' '[:upper:]_' <<< "$triple")" "$gnu" "${triple//-/_}" "$gnu" > /cross.env

WORKDIR /build/backend-rs

# The dependencies compile in their own layer, from the manifests and empty stand-ins for the
# sources: a source change then rebuilds the picsou crate alone. `cargo clean` drops what the
# stand-ins built: cargo keeps it when the real sources are older, and ships an empty binary.
# It needs --target (it ignores CARGO_BUILD_TARGET), and the layer fails if the binary survives.
COPY backend-rs/Cargo.toml backend-rs/Cargo.lock ./
COPY backend-rs/macros/ macros/
# hadolint ignore=SC1091
RUN mkdir -p src tests/integration \
    && echo 'fn main() {}' > src/main.rs \
    && touch src/lib.rs tests/integration/main.rs \
    && . /cross.env \
    && cargo build --release --locked --bin picsou \
    && cargo clean --release --package picsou --target "$CARGO_BUILD_TARGET" \
    && [ ! -e "target/$CARGO_BUILD_TARGET/release/picsou" ] \
    && rm -r src tests

# The migrations (backend-rs/migrations) are embedded in the binary (include_dir!).
# Only the binary stays in the layer: a build that exports its cache uploads it each time.
COPY backend-rs/ .
# hadolint ignore=SC1091
RUN . /cross.env \
    && cargo build --release --locked --bin picsou \
    && cp "target/$CARGO_BUILD_TARGET/release/picsou" /picsou \
    && rm -r target

# ---------- Stage 3: Runtime ----------
# One non-root process: the Rust backend serves the SPA and the API on 8080 and is PID 1.
# A shell and coreutils stay: Compose's data-permissions service runs chown in this image,
# and the Helm migration job runs the release CLI through sh.
FROM debian:bookworm-slim

RUN groupadd --gid 1000 picsou \
    && useradd --uid 1000 --gid 1000 --home-dir /app --no-create-home --shell /usr/sbin/nologin picsou \
    && mkdir -p /data \
    && chown picsou:picsou /data

ARG APP_VERSION=dev
ENV APP_VERSION=$APP_VERSION

# Copy built frontend; the backend serves it from SPA_LOCATION
COPY --from=frontend-build /build/frontend/dist /app/spa
ENV SPA_LOCATION=/app/spa

# The app generates and persists its secrets here on first start (SecretBootstrap).
# A folder, not a secret.
# hadolint ignore=DL3064
ENV PICSOU_SECRETS_DIR=/data/.secrets

# Sync at startup stays off unless the operator turns it on.
ENV APP_STARTUP_SYNC_ENABLED=false

# Copy built backend; `picsou migrate|verify` is the release migration check.
COPY --from=backend-build /picsou /usr/local/bin/picsou

WORKDIR /app
USER 1000:1000
EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=3s --start-period=15s --retries=3 \
    CMD ["/usr/local/bin/picsou", "healthcheck"]

ENTRYPOINT ["picsou"]
