# Base: services/base, target `camoufox` (BNP's edge filters non-browser clients; the login runs
# in a real, headful Firefox under the shared Xvfb entrypoint).
ARG SIDECAR_BASE=picsou-sidecar-camoufox
FROM ${SIDECAR_BASE}

WORKDIR /app

RUN groupadd -r bnp && useradd -r -g bnp -m -d /home/bnp bnp \
    && chown -R bnp:bnp /app
USER bnp

COPY --chown=bnp:bnp main.py bank.py parsers.py ./

ENV SIDECAR_PORT=8001 \
    XVFB_DISPLAY=:98 \
    XVFB_SCREEN=1366x768x24
EXPOSE 8001
CMD ["sh", "/opt/sidecar-base/entrypoint.sh"]
