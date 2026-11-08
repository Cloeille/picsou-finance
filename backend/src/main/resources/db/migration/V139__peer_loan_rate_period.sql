-- A loan can be agreed on a monthly or an annual nominal rate. The annual value stays
-- the normalized figure the calculator uses; the period is what the member typed, kept
-- for display so reopening the form shows the rate as agreed.
ALTER TABLE peer_loan_terms
    ADD COLUMN rate_period VARCHAR(10) NOT NULL DEFAULT 'ANNUAL';

ALTER TABLE peer_loan_terms
    ADD CONSTRAINT ck_peer_loan_terms_rate_period
    CHECK (rate_period IN ('ANNUAL', 'MONTHLY'));
