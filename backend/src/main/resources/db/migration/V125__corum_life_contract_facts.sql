-- Contract-level facts a CORUM Life sync reads, and the wealth tier CORUM's own
-- asset-class view gives a line.
--
-- provider_invested_eur is the contract's net invested amount. The per-line costs
-- CORUM reports leave out money switched between funds, so they do not add up to it
-- and only this figure gives the contract's real gain. provider_paid_in_eur is the
-- gross amount paid in; the scheduled payment is the programmed savings plan. All
-- four are NULL for every account no connector fills, and NULL means "not reported".
--
-- account_holding.provider_wealth_tier is what the provider says a line is, never
-- the member's verdict: that one stays in holding_classification (V83), keyed on the
-- ticker so it survives the holding rewrite every sync does. VARCHAR + a named CHECK,
-- as V83 does for the same values.
ALTER TABLE account
    ADD COLUMN provider_invested_eur      NUMERIC(20, 8),
    ADD COLUMN provider_paid_in_eur       NUMERIC(20, 8),
    ADD COLUMN scheduled_payment_eur      NUMERIC(20, 8),
    ADD COLUMN scheduled_payment_cadence  VARCHAR(16),
    ADD CONSTRAINT ck_account_scheduled_payment_cadence CHECK (scheduled_payment_cadence IS NULL
        OR scheduled_payment_cadence IN ('WEEKLY', 'BIWEEKLY', 'MONTHLY', 'QUARTERLY', 'YEARLY'));

ALTER TABLE account_holding
    ADD COLUMN provider_wealth_tier VARCHAR(20),
    ADD CONSTRAINT ck_account_holding_provider_wealth_tier CHECK (provider_wealth_tier IS NULL
        OR provider_wealth_tier IN ('SAFETY_NET', 'REAL_ESTATE', 'EQUITY', 'CRYPTO', 'ALTERNATIVE'));
