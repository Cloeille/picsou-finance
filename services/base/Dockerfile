# Shared base for the Picsou sidecars. Two targets, each published once:
#   python   - Python runtime and the shared pins (browserless sidecars)
#   chromium - python + Playwright and its Chromium (browser sidecars)
# A sidecar image adds only its user and its code on top, so Docker stores and
# pulls these layers once for every sidecar. See
# docs/decisions/2026-10-21-shared-sidecar-base-image.md.

# 3.12.14-slim-bookworm, multi-arch index (amd64 CI and arm64 local).
FROM python:3.15-slim-bookworm@sha256:4e03260adeeebafccd084f3584e6b84823aba5651cfb53ef8a078ed441ddf9cd AS python

WORKDIR /app

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1

# The backend's admin instance-status check reads this via /health to spot a
# sidecar still on a pre-base image. The chromium target overrides it.
ENV PICSOU_SIDECAR_BASE=sidecar-python

COPY requirements.txt /opt/sidecar-base/requirements.txt
RUN pip install --no-cache-dir -r /opt/sidecar-base/requirements.txt

# The shared sidecar packages: picsou_sidecar for every bank, euro_information
# for CIC and Credit Mutuel. A .pth file rather than PYTHONPATH: the sidecar test
# jobs set PYTHONPATH=/app, which would hide them.
# .dockerignore excludes test helpers and local bytecode; CI mounts helpers read-only.
COPY picsou_sidecar /opt/sidecar-base/lib/picsou_sidecar
# Copy runtime modules explicitly: euro_information/testing.py contains shared
# test fixtures and must remain outside production images.
COPY euro_information/__init__.py euro_information/client.py euro_information/forms.py /opt/sidecar-base/lib/euro_information/
RUN echo /opt/sidecar-base/lib > "$(python -c 'import site; print(site.getsitepackages()[0])')/picsou-sidecar.pth" \
    && python -c "import picsou_sidecar"

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
