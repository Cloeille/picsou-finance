-- V74: Per-product breakdown behind a crypto exchange account's holdings.
--
-- An exchange can hold the same asset under several products at once (spot, staked, lent), which
-- account_holding cannot express: it is the valuation model shared by every connector and is
-- unique on (account_id, ticker). These rows are display-only and fully derived — each sync
-- rewrites them wholesale — so they carry no cost basis and nothing computes net worth from them.
--
-- ON DELETE CASCADE: the rows are meaningless without their account, and an exchange account is
-- deleted whenever the user removes the session.

CREATE TABLE crypto_exchange_position (
    id             BIGSERIAL PRIMARY KEY,
    account_id     BIGINT        NOT NULL REFERENCES account(id) ON DELETE CASCADE,
    product        VARCHAR(20)   NOT NULL,
    ticker         VARCHAR(30)   NOT NULL,
    quantity       NUMERIC(20,8) NOT NULL,
    -- Nullable: only exchanges that report accrued yield can split the quantity in two.
    principal      NUMERIC(20,8),
    interest       NUMERIC(20,8),
    last_synced_at TIMESTAMPTZ,
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_crypto_exchange_position_account_product_ticker UNIQUE (account_id, product, ticker)
);

CREATE INDEX idx_crypto_exchange_position_account ON crypto_exchange_position (account_id);
