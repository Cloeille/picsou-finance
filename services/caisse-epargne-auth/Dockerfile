# Base: services/base, target `chromium` (Python, the shared pins and Chromium).
# Compose and CI build it first (`docker build --target chromium --tag
# picsou-sidecar-chromium services/base`); the publish workflow passes the GHCR
# image by digest instead.
ARG SIDECAR_BASE=picsou-sidecar-chromium
FROM ${SIDECAR_BASE}

RUN groupadd -r caisse && useradd -r -g caisse caisse

COPY main.py replay.py fetcher.py accounts_parser.py per_parser.py per_client.py login.py keypad_table.py ./

USER caisse
EXPOSE 8001
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8001"]
