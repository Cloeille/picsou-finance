FROM python:3.12-slim

WORKDIR /app

RUN groupadd -r revoauth && useradd -r -g revoauth -m -d /home/revoauth revoauth

# System libraries for Camoufox's Firefox engine + Xvfb. POST /sync runs a headful
# Firefox (headless=False) when re-auth is needed, so the server runs under a virtual
# X display started by entrypoint.sh — we drive Xvfb directly (the xvfb-run wrapper
# hangs on this trixie base and never exec's the server, and would need xauth; the
# direct Xvfb launch needs neither xvfb-run nor xauth).
RUN apt-get update && apt-get install -y --no-install-recommends \
        xvfb \
        libgtk-3-0 libdbus-glib-1-2 libx11-xcb1 libxcb-shm0 libxcomposite1 \
        libxcursor1 libxdamage1 libxi6 libxtst6 libxrandr2 libxt6 libxext6 \
        libasound2 libpangocairo-1.0-0 libpango-1.0-0 libcairo2 libgdk-pixbuf-2.0-0 \
        libnss3 libnspr4 libfontconfig1 fonts-liberation ca-certificates \
        libgl1 libegl1 libgbm1 \
    && apt-get clean && rm -rf /var/lib/apt/lists/*

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Persistent per-member session profiles live here (mount a volume in docker-compose).
# camoufox[geoip] writes GeoLite2-City.mmdb INTO its own package dir at fetch time, so that
# dir must be owned by the runtime user too — otherwise `camoufox fetch` below (run as
# revoauth) hits PermissionError writing into the root-owned site-packages.
RUN mkdir -p /data/revolut-profiles /tmp/.X11-unix \
    && chmod 1777 /tmp/.X11-unix \
    && chown -R revoauth:revoauth /app /data/revolut-profiles \
       "$(python -c 'import camoufox, os; print(os.path.dirname(os.path.abspath(camoufox.__file__)))')"
USER revoauth

# Download Camoufox's stealth Firefox (into ~/.cache/camoufox) + the GeoIP DB (into the
# camoufox package dir, chowned above) as the runtime user so both are readable at runtime.
# Camoufox browser pinned: `camoufox fetch` installs the latest release, and v156.0.1-beta.33
# breaks the AMEX login (UnknownProperty). Reuse the package's fetcher with a fixed release.
COPY pin_camoufox.py .
RUN python pin_camoufox.py

COPY --chown=revoauth:revoauth main.py entrypoint.sh ./

ENV REVOLUT_PROFILES_DIR=/data/revolut-profiles
EXPOSE 8002

CMD ["sh", "entrypoint.sh"]
