# Base: services/base, target `python` (Python and the shared pins, no browser).
# Compose and CI build it first (`docker build --target python --tag
# picsou-sidecar-python services/base`); the publish workflow passes the GHCR
# image by digest instead.
ARG SIDECAR_BASE=picsou-sidecar-python
FROM ${SIDECAR_BASE}

RUN groupadd -r lbp && useradd -r -g lbp lbp

COPY main.py bank.py ./

USER lbp
EXPOSE 8001
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8001"]
