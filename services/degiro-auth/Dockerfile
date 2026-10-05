FROM python:3.12-slim

WORKDIR /app

RUN groupadd -r degiro && useradd -r -g degiro degiro

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY main.py portfolio_parser.py ./

RUN chown -R degiro:degiro /app
USER degiro

EXPOSE 8001
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8001"]
