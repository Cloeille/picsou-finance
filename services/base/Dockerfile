# Shared base for the Picsou sidecars. Three targets, each published once:
#   python   - Python runtime and the shared pins (browserless sidecars)
#   chromium - python + Playwright and its Chromium (browser sidecars)
#   camoufox - python + Playwright 1.55, pinned Camoufox Firefox and Xvfb
# A sidecar image adds only its user and its code on top, so Docker stores and
# pulls these layers once for every sidecar. See
# docs/decisions/2026-10-21-shared-sidecar-base-image.md.

# 3.12.14-slim-bookworm, multi-arch index (amd64 CI and arm64 local).
FROM python:3.12-slim-bookworm@sha256:392307d22300de8b5986851a12d9176dfc0fc073e65bf6523ebd7dcbeb23564e AS python

WORKDIR /app

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1

# The backend's admin instance-status check reads this via /health to spot a
# sidecar still on a pre-base image. The chromium target overrides it.
ENV PICSOU_SIDECAR_BASE=sidecar-python

COPY requirements.txt /opt/sidecar-base/requirements.txt
RUN pip install --no-cache-dir -r /opt/sidecar-base/requirements.txt

FROM python AS chromium

ENV PICSOU_SIDECAR_BASE=sidecar-chromium

# Chromium-only runtime. The official Playwright image also ships Firefox and
# WebKit, which no sidecar uses. Playwright's own `--with-deps` option would pull
# Ubuntu-only font packages that do not exist on Debian, hence the explicit list.
# openssl is the TLS fixture tool for corum-auth's navigation security test.
RUN apt-get update && apt-get install -y --no-install-recommends \
        libnss3 libnspr4 libatk1.0-0 libatk-bridge2.0-0 libcups2 \
        libdrm2 libxkbcommon0 libxcomposite1 libxdamage1 libxfixes3 \
        libxrandr2 libgbm1 libasound2 libpango-1.0-0 libcairo2 \
        libx11-6 libx11-xcb1 libxcb1 libxext6 \
        ca-certificates fonts-liberation openssl \
    && apt-get clean && rm -rf /var/lib/apt/lists/*

# Outside /app on purpose: a sidecar that runs `chown -R <user> /app` must not
# copy Chromium into a new layer. World-readable so every sidecar user can run it.
ENV PLAYWRIGHT_BROWSERS_PATH=/opt/playwright-browsers

COPY requirements-chromium.txt /opt/sidecar-base/requirements-chromium.txt
RUN pip install --no-cache-dir -r /opt/sidecar-base/requirements-chromium.txt \
    && playwright install chromium \
    && chmod -R a+rX /opt/playwright-browsers

FROM python AS camoufox

ENV PICSOU_SIDECAR_BASE=sidecar-camoufox \
    XDG_CACHE_HOME=/opt/camoufox-cache

# Firefox runtime, including GL/Mesa for headless launches, plus a virtual display
# for headful login. Keep this separate from the Chromium/Playwright 1.52 target.
RUN apt-get update && apt-get install -y --no-install-recommends \
        xvfb \
        libgtk-3-0 libdbus-glib-1-2 libx11-xcb1 libxcb-shm0 libxcomposite1 \
        libxcursor1 libxdamage1 libxi6 libxtst6 libxrandr2 libxt6 libxext6 \
        libasound2 libpangocairo-1.0-0 libpango-1.0-0 libcairo2 libgdk-pixbuf-2.0-0 \
        libnss3 libnspr4 libfontconfig1 fonts-liberation ca-certificates \
        libgl1 libegl1 libgbm1 \
    && apt-get clean && rm -rf /var/lib/apt/lists/*

COPY requirements-camoufox.txt /opt/sidecar-base/requirements-camoufox.txt
RUN pip install --no-cache-dir -r /opt/sidecar-base/requirements-camoufox.txt

# Download once as root, including the package-local GeoIP DB. Every sidecar user
# reads the same cache; neither /app chowns nor per-user homes duplicate Firefox.
COPY pin_camoufox.py entrypoint.sh smoke_camoufox.py /opt/sidecar-base/
RUN python /opt/sidecar-base/pin_camoufox.py \
    && chmod -R a+rX /opt/camoufox-cache \
    && mkdir -p /tmp/.X11-unix \
    && chmod 1777 /tmp/.X11-unix
