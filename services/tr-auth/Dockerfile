FROM python:3.12-slim

WORKDIR /app

RUN groupadd -r trauth && useradd -r -g trauth trauth

# Chromium system deps for Debian bookworm (subset of playwright --with-deps,
# excluding Ubuntu-only font packages unavailable on Debian)
RUN apt-get update && apt-get install -y --no-install-recommends \
        libnss3 libnspr4 libatk1.0-0 libatk-bridge2.0-0 libcups2 \
        libdrm2 libxkbcommon0 libxcomposite1 libxdamage1 libxfixes3 \
        libxrandr2 libgbm1 libasound2 libpango-1.0-0 libcairo2 \
        libx11-6 libx11-xcb1 libxcb1 libxext6 \
        ca-certificates fonts-liberation \
    && apt-get clean && rm -rf /var/lib/apt/lists/*

ENV PLAYWRIGHT_BROWSERS_PATH=/app/playwright-browsers

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt \
    && playwright install chromium

COPY main.py .

RUN chown -R trauth:trauth /app
USER trauth

EXPOSE 8001
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8001", "--timeout-keep-alive", "65"]
