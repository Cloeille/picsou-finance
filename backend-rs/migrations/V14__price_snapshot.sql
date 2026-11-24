CREATE TABLE price_snapshot (
    id          BIGSERIAL PRIMARY KEY,
    ticker      VARCHAR(30)   NOT NULL,
    date        DATE          NOT NULL,
    price_eur   NUMERIC(20, 8) NOT NULL,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_price_snapshot_ticker_date UNIQUE (ticker, date)
);

CREATE INDEX idx_price_snapshot_ticker_date ON price_snapshot (ticker, date);
