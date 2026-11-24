-- V136 capped the annual rate at 100 %. Short-term loans may carry a higher nominal
-- rate (12 %/month is 144 %/year), and the API no longer caps it. Replace the old
-- upper bound with a non-negative guard; the column is already NUMERIC(12,6) (V140).
ALTER TABLE peer_loan_terms DROP CONSTRAINT ck_peer_loan_terms_rate;

ALTER TABLE peer_loan_terms ADD CONSTRAINT ck_peer_loan_terms_rate
    CHECK (annual_rate IS NULL OR annual_rate >= 0);
