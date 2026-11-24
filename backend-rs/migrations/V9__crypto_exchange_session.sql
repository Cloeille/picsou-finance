-- V7: Crypto exchange sessions (Binance, Kraken, etc.)

CREATE TABLE crypto_exchange_session (
    id              BIGSERIAL PRIMARY KEY,
    exchange_type   VARCHAR(20)  NOT NULL,
    api_key         VARCHAR(200) NOT NULL,
    api_secret      VARCHAR(500) NOT NULL,
    status          VARCHAR(20)  NOT NULL DEFAULT 'CONNECTED',
    last_synced_at  TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
