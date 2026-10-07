-- Recurring series detection (1.1.0, phase B).
-- Detected subscriptions / direct debits / regular income, surfaced for confirmation
-- and projected onto a calendar of upcoming due dates.

CREATE TYPE recurring_cadence AS ENUM ('WEEKLY', 'BIWEEKLY', 'MONTHLY', 'QUARTERLY', 'YEARLY');

-- SUGGESTED  -> auto-detected, awaiting the user's confirm/ignore
-- CONFIRMED  -> user accepted; drives the calendar
-- IGNORED    -> user dismissed; detection must not resurrect it
CREATE TYPE recurring_status AS ENUM ('SUGGESTED', 'CONFIRMED', 'IGNORED');

CREATE TABLE recurring_series (
    id              BIGSERIAL PRIMARY KEY,
    member_id       BIGINT            NOT NULL REFERENCES family_member(id) ON DELETE CASCADE,
    label           VARCHAR(255)      NOT NULL,
    counterparty    VARCHAR(255),
    expected_amount NUMERIC(20,2)     NOT NULL,
    cadence         recurring_cadence NOT NULL,
    next_due_date   DATE,
    last_seen_date  DATE,
    category_id     BIGINT            REFERENCES category(id) ON DELETE SET NULL,
    status          recurring_status  NOT NULL DEFAULT 'SUGGESTED',
    created_at      TIMESTAMPTZ       NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ       NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_recurring_member ON recurring_series(member_id);

-- Link transactions to the series they belong to (set during detection).
ALTER TABLE transaction
  ADD CONSTRAINT fk_transaction_recurring_series
  FOREIGN KEY (recurring_series_id) REFERENCES recurring_series(id) ON DELETE SET NULL;
