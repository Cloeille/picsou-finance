FROM python:3.12-slim-bookworm

WORKDIR /app

RUN groupadd -r sofidy && useradd -r -g sofidy sofidy

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# No browser: the Sofidy client space serves its pages from PHP and the login is
# a plain form POST to an Ajax endpoint, so HTTPS alone authenticates and this
# image stays a tenth the size of the Playwright sidecars.
COPY main.py positions_parser.py ./

USER sofidy
EXPOSE 8001
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8001"]
