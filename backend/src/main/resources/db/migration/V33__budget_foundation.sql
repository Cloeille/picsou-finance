-- Budget & Cashflow foundation (1.1.0).
-- Adds categories, a categorization rules engine, per-member budget settings,
-- and enriches the transaction table so bank-synced transactions can be
-- categorized, deduplicated and linked to recurring series.

-- Category kind drives three behaviours from a single concept:
--   INCOME   -> counted as cashflow income, ignored by expense envelopes
--   EXPENSE  -> counted by envelopes and as cashflow outflow
--   TRANSFER -> excluded from cashflow AND envelopes, feeds allocation flux
CREATE TYPE category_kind AS ENUM ('INCOME', 'EXPENSE', 'TRANSFER');

CREATE TABLE category (
    id         BIGSERIAL PRIMARY KEY,
    member_id  BIGINT        NOT NULL REFERENCES family_member(id) ON DELETE CASCADE,
    name       VARCHAR(100)  NOT NULL,
    kind       category_kind NOT NULL,
    color      VARCHAR(7)    NOT NULL DEFAULT '#6366f1',
    icon       VARCHAR(50),
    is_default BOOLEAN       NOT NULL DEFAULT FALSE,
    archived   BOOLEAN       NOT NULL DEFAULT FALSE,
    sort_order INTEGER       NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_category_member ON category(member_id);

CREATE TABLE categorization_rule (
    id          BIGSERIAL PRIMARY KEY,
    member_id   BIGINT       NOT NULL REFERENCES family_member(id) ON DELETE CASCADE,
    match_type  VARCHAR(20)  NOT NULL,       -- COUNTERPARTY | KEYWORD
    pattern     VARCHAR(255) NOT NULL,
    category_id BIGINT       NOT NULL REFERENCES category(id) ON DELETE CASCADE,
    priority    INTEGER      NOT NULL DEFAULT 0,
    source      VARCHAR(10)  NOT NULL DEFAULT 'USER',  -- USER | AUTO
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_rule_member_priority ON categorization_rule(member_id, priority DESC);

CREATE TABLE budget_settings (
    id              BIGSERIAL PRIMARY KEY,
    member_id       BIGINT      NOT NULL UNIQUE REFERENCES family_member(id) ON DELETE CASCADE,
    cycle_start_day SMALLINT    NOT NULL DEFAULT 1 CHECK (cycle_start_day BETWEEN 1 AND 28),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Enrich transactions for budgeting. The legacy free-string `category` column
-- (Finary import) is kept; `category_id` is the managed relation used by budgets.
ALTER TABLE transaction
  ADD COLUMN category_id         BIGINT       REFERENCES category(id) ON DELETE SET NULL,
  ADD COLUMN counterparty        VARCHAR(255),
  ADD COLUMN external_id         VARCHAR(255),
  ADD COLUMN recurring_series_id BIGINT;       -- FK added in V35 (recurring)

CREATE INDEX idx_transaction_category ON transaction(category_id);

-- Deduplicate bank-synced transactions by their provider entry reference.
-- Partial index so manual transactions (external_id IS NULL) are unaffected.
CREATE UNIQUE INDEX idx_transaction_account_external
    ON transaction(account_id, external_id) WHERE external_id IS NOT NULL;
