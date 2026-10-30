# Base: services/base, target `chromium` (Python, the shared pins and Chromium).
# Compose and CI build it first (`docker build --target chromium --tag
# picsou-sidecar-chromium services/base`); the publish workflow passes the GHCR
# image by digest instead.
ARG SIDECAR_BASE=picsou-sidecar-chromium
FROM ${SIDECAR_BASE}

RUN groupadd -r boursedirect && useradd -r -g boursedirect boursedirect

COPY main.py portfolio_parser.py ./

USER boursedirect
EXPOSE 8001
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8001"]
