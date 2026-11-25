-- V1: Core schema — users, accounts, balance snapshots

CREATE TABLE app_user (
    id           BIGSERIAL PRIMARY KEY,
    username     VARCHAR(50)  NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE TYPE account_type AS ENUM (
    'LEP', 'PEA', 'COMPTE_TITRES', 'CRYPTO', 'CHECKING', 'SAVINGS', 'OTHER'
);

CREATE TABLE account (
    id                      BIGSERIAL PRIMARY KEY,
    name                    VARCHAR(100)   NOT NULL,
    type                    account_type   NOT NULL DEFAULT 'OTHER',
    provider                VARCHAR(100),
    currency                VARCHAR(10)    NOT NULL DEFAULT 'EUR',
    current_balance         NUMERIC(20, 8) NOT NULL DEFAULT 0,
    last_synced_at          TIMESTAMPTZ,
    gocardless_account_id   VARCHAR(100),
    is_manual               BOOLEAN        NOT NULL DEFAULT TRUE,
    color                   VARCHAR(7)     NOT NULL DEFAULT '#6366f1',
    ticker                  VARCHAR(20),
    created_at              TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ    NOT NULL DEFAULT NOW()
);

CREATE TABLE balance_snapshot (
    id          BIGSERIAL PRIMARY KEY,
    account_id  BIGINT         NOT NULL REFERENCES account(id) ON DELETE CASCADE,
    date        DATE           NOT NULL,
    balance     NUMERIC(20, 8) NOT NULL,
    created_at  TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    UNIQUE (account_id, date)
);

CREATE INDEX idx_balance_snapshot_account_date ON balance_snapshot(account_id, date DESC);
CREATE INDEX idx_account_gocardless_id ON account(gocardless_account_id) WHERE gocardless_account_id IS NOT NULL;
