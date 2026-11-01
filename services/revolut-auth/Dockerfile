ARG SIDECAR_BASE=picsou-sidecar-camoufox
FROM ${SIDECAR_BASE}

WORKDIR /app

# Existing profile volumes belong to UID 999; base packages can change automatic allocation.
RUN groupadd -r revoauth && useradd -r -u 999 -g revoauth -m -d /home/revoauth revoauth \
    && mkdir -p /data/revolut-profiles \
    && chown -R revoauth:revoauth /app /data/revolut-profiles
USER revoauth

COPY --chown=revoauth:revoauth main.py ./

ENV REVOLUT_PROFILES_DIR=/data/revolut-profiles \
    SIDECAR_PORT=8002 \
    XVFB_DISPLAY=:99 \
    XVFB_SCREEN=1280x1024x24
EXPOSE 8002
CMD ["sh", "/opt/sidecar-base/entrypoint.sh"]
