-- Short-term peer loans can carry high nominal rates: a 10 %/month loan is a
-- 120 %/year one, which the old NUMERIC(5,4) (max 9.9999) could not even store.
-- Widening precision and scale keeps every existing rate unchanged.
ALTER TABLE peer_loan_terms
    ALTER COLUMN annual_rate TYPE NUMERIC(12,6);
