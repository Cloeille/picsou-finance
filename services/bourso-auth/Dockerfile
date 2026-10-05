FROM python:3.12-slim-bookworm

WORKDIR /app

RUN groupadd -r bourso && useradd -r -g bourso bourso

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# No browser: BoursoBank's only anti-bot token is handed out by the server and
# echoed back, so plain HTTPS is enough and this image stays a tenth the size of
# the Playwright sidecars.
COPY main.py virtual_pad.py accounts_parser.py ./

USER bourso
EXPOSE 8001
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8001"]
