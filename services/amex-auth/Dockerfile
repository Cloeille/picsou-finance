ARG SIDECAR_BASE=picsou-sidecar-camoufox
FROM ${SIDECAR_BASE}

WORKDIR /app

RUN groupadd -r amex && useradd -r -g amex -m -d /home/amex amex \
    && chown -R amex:amex /app
USER amex

COPY --chown=amex:amex main.py ./

ENV SIDECAR_PORT=8001 \
    XVFB_DISPLAY=:98 \
    XVFB_SCREEN=1366x768x24
EXPOSE 8001
CMD ["sh", "/opt/sidecar-base/entrypoint.sh"]
