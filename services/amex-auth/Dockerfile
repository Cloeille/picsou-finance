FROM python:3.12-slim-bookworm

WORKDIR /app

RUN groupadd -r amex && useradd -r -g amex -m -d /home/amex amex

# System libraries for Camoufox's Firefox engine + Xvfb. AMEX's Akamai Bot Manager
# blocks the login POST from a plain Playwright Chromium fingerprint outright (see
# main.py's module docstring), so the sidecar drives a real, stealth-patched Firefox
# instead and needs a virtual X display for it (entrypoint.sh starts Xvfb directly --
# the xvfb-run wrapper hangs on this base image and never exec's the server).
RUN apt-get update && apt-get install -y --no-install-recommends \
        xvfb \
        libgtk-3-0 libdbus-glib-1-2 libx11-xcb1 libxcb-shm0 libxcomposite1 \
        libxcursor1 libxdamage1 libxi6 libxtst6 libxrandr2 libxt6 libxext6 \
        libasound2 libpangocairo-1.0-0 libpango-1.0-0 libcairo2 libgdk-pixbuf-2.0-0 \
        libnss3 libnspr4 libfontconfig1 fonts-liberation ca-certificates \
        libgl1 libegl1 libgbm1 \
    && apt-get clean && rm -rf /var/lib/apt/lists/*

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# camoufox[geoip] writes GeoLite2-City.mmdb INTO its own package dir at fetch time, so
# that dir must be owned by the runtime user too -- otherwise `camoufox fetch` below
# (run as amex) hits PermissionError writing into the root-owned site-packages.
RUN mkdir -p /tmp/.X11-unix \
    && chmod 1777 /tmp/.X11-unix \
    && chown -R amex:amex /app \
       "$(python -c 'import camoufox, os; print(os.path.dirname(os.path.abspath(camoufox.__file__)))')"
USER amex

# Download Camoufox's stealth Firefox (into ~/.cache/camoufox) + the GeoIP DB (into the
# camoufox package dir, chowned above) as the runtime user so both are readable at runtime.
# Camoufox browser pinned: `camoufox fetch` installs the latest release, and v156.0.1-beta.33
# breaks the AMEX login (UnknownProperty). Reuse the package's fetcher with a fixed release.
COPY pin_camoufox.py .
RUN python pin_camoufox.py

COPY --chown=amex:amex main.py entrypoint.sh ./

EXPOSE 8001
CMD ["sh", "entrypoint.sh"]
