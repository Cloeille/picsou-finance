# 3.12.14-slim-bookworm, multi-arch index (amd64 CI and arm64 local).
FROM python:3.12-slim-bookworm@sha256:392307d22300de8b5986851a12d9176dfc0fc073e65bf6523ebd7dcbeb23564e

WORKDIR /app

RUN groupadd -r browser && useradd -r -g browser -m -d /home/browser browser

# Chromium-only runtime, same apt list as services/corum-auth/Dockerfile.
# openssl is unused at runtime; it is kept so the image matches that list.
RUN apt-get update && apt-get install -y --no-install-recommends \
        libnss3 libnspr4 libatk1.0-0 libatk-bridge2.0-0 libcups2 \
        libdrm2 libxkbcommon0 libxcomposite1 libxdamage1 libxfixes3 \
        libxrandr2 libgbm1 libasound2 libpango-1.0-0 libcairo2 \
        libx11-6 libx11-xcb1 libxcb1 libxext6 \
        ca-certificates fonts-liberation openssl \
    && apt-get clean && rm -rf /var/lib/apt/lists/*

ENV PLAYWRIGHT_BROWSERS_PATH=/app/playwright-browsers \
    PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    HOME=/home/browser

# Build context is services/ so the shared pin file and the client package
# are both visible. Playwright is used only to install and locate Chromium.
COPY requirements-shared.txt .
RUN pip install --no-cache-dir -r requirements-shared.txt \
    && playwright install chromium \
    && chown -R browser:browser /app/playwright-browsers

# uvicorn's WebSocket implementation is an extra; without it the CDP socket 404s.
RUN pip install --no-cache-dir 'websockets==17.2'

COPY browser-service/main.py /app/main.py
COPY shared/picsou_browser /app/picsou_browser

USER browser
EXPOSE 8001
# Access logs include the session id. Lifecycle logs already record a prefix.
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8001", "--no-access-log"]
