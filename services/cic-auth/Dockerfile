# Base: services/base, target `python` (Python and the shared pins, no browser).
# Compose and CI build it first (`docker build --target python --tag
# picsou-sidecar-python services/base`); the publish workflow passes the GHCR
# image by digest instead.
ARG SIDECAR_BASE=picsou-sidecar-python
FROM ${SIDECAR_BASE}

RUN groupadd -r cic && useradd -r -g cic cic

COPY main.py accounts_parser.py ./

RUN chown -R cic:cic /app
USER cic
EXPOSE 8001
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8001"]
