# Base: services/base, target `chromium` (Python, the shared pins and Chromium).
# Compose and CI build it first (`docker build --target chromium --tag
# picsou-sidecar-chromium services/base`); the publish workflow passes the GHCR
# image by digest instead.
ARG SIDECAR_BASE=picsou-sidecar-chromium
FROM ${SIDECAR_BASE}

RUN groupadd -r corum && useradd -r -g corum corum

# Build context is services/ so picsou_browser can be copied next to the code.
COPY shared/picsou_browser /app/picsou_browser
COPY corum-auth/main.py corum-auth/positions_parser.py /app/

USER corum
EXPOSE 8001
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8001"]
