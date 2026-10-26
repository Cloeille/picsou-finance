# Base: services/base, target `chromium`: the same Python, pins and Chromium as
# the Docker sidecars. CI builds it first (`docker build --target chromium --tag
# picsou-sidecar-chromium services/base`). Never published: desktop app only (#235).
ARG SIDECAR_BASE=picsou-sidecar-chromium
FROM ${SIDECAR_BASE}

RUN groupadd -r browser && useradd -r -g browser -m -d /home/browser browser

# uvicorn's WebSocket implementation is an extra; without it the CDP socket 404s.
RUN pip install --no-cache-dir 'websockets==17.2'

ENV HOME=/home/browser

# Build context is services/ so the client package is visible.
COPY browser-service/main.py /app/main.py
COPY shared/picsou_browser /app/picsou_browser

USER browser
EXPOSE 8001
# Access logs include the session id. Lifecycle logs already record a prefix.
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8001", "--no-access-log"]
