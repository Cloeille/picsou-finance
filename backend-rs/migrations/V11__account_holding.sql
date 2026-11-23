-- V11: Per-holding breakdown for crypto exchange and PEA/CTO accounts

CREATE TABLE account_holding (
    id              BIGSERIAL PRIMARY KEY,
    account_id      BIGINT        NOT NULL REFERENCES account(id) ON DELETE CASCADE,
    ticker          VARCHAR(30)   NOT NULL,
    name            VARCHAR(100),
    quantity        NUMERIC(20, 8) NOT NULL,
    average_buy_in  NUMERIC(20, 8),
    current_price   NUMERIC(20, 8),
    last_synced_at  TIMESTAMPTZ,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    UNIQUE (account_id, ticker)
);
