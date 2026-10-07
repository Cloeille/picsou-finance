-- Savings interest configuration (livrets: Livret A, LDDS, LEP, and generic commercial livrets).
-- Interest is a PROJECTION ONLY — these rows never drive balance or snapshot writes.

CREATE TYPE savings_product AS ENUM ('LIVRET_A', 'LDDS', 'LEP', 'COMMERCIAL');
CREATE TYPE rate_basis AS ENUM ('GROSS', 'NET');

CREATE TABLE savings_interest_config (
    id           BIGSERIAL PRIMARY KEY,
    account_id   BIGINT         NOT NULL UNIQUE REFERENCES account(id) ON DELETE CASCADE,
    product      savings_product NOT NULL,
    -- Annual rate stored as a percentage (e.g. 2.4 for 2.4 %). User-overridable.
    annual_rate  NUMERIC(6,4)   NOT NULL,
    rate_basis   rate_basis     NOT NULL DEFAULT 'NET',
    -- Only meaningful for COMMERCIAL + GROSS; ignored for regulated products.
    tax_rate_pct NUMERIC(5,2),
    -- Optional ceiling defined by regulation (e.g. 22 950 € for Livret A). Informational only.
    ceiling      NUMERIC(20,2),
    created_at   TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ    NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_savings_interest_config_account ON savings_interest_config(account_id);
