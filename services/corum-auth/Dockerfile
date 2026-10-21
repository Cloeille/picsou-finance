# 3.12.14-slim-bookworm, multi-arch index (amd64 CI and arm64 local).
FROM python:3.12-slim-bookworm@sha256:392307d22300de8b5986851a12d9176dfc0fc073e65bf6523ebd7dcbeb23564e

WORKDIR /app

RUN groupadd -r corum && useradd -r -g corum corum

# Chromium-only runtime, matching the active sidecar image ADR. The official
# Playwright image also ships Firefox and WebKit, which this service never uses.
# openssl is the TLS fixture tool for the navigation security test, not a
# runtime dependency of the sidecar itself.
RUN apt-get update && apt-get install -y --no-install-recommends \
        libnss3 libnspr4 libatk1.0-0 libatk-bridge2.0-0 libcups2 \
        libdrm2 libxkbcommon0 libxcomposite1 libxdamage1 libxfixes3 \
        libxrandr2 libgbm1 libasound2 libpango-1.0-0 libcairo2 \
        libx11-6 libx11-xcb1 libxcb1 libxext6 \
        ca-certificates fonts-liberation openssl \
    && apt-get clean && rm -rf /var/lib/apt/lists/*

ENV PLAYWRIGHT_BROWSERS_PATH=/app/playwright-browsers \
    PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1

# Build context is services/ so the shared pin file and picsou_browser can be copied.
# The image keeps its own Chromium for the local-launch fallback.
COPY requirements-shared.txt .
RUN pip install --no-cache-dir -r requirements-shared.txt \
    && playwright install chromium

COPY shared/picsou_browser /app/picsou_browser
COPY corum-auth/main.py corum-auth/positions_parser.py /app/

USER corum
EXPOSE 8001
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8001"]
