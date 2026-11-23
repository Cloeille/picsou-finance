-- V12: Finary transaction import

CREATE TABLE transaction (
    id              BIGSERIAL PRIMARY KEY,
    account_id      BIGINT          NOT NULL REFERENCES account(id) ON DELETE CASCADE,
    date            DATE            NOT NULL,
    description     VARCHAR(255)    NOT NULL DEFAULT '',
    amount          NUMERIC(20, 8)  NOT NULL,
    type            VARCHAR(100),
    category        VARCHAR(100),
    native_currency VARCHAR(10)     NOT NULL DEFAULT 'EUR',
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_transaction_account_date ON transaction(account_id, date DESC);
