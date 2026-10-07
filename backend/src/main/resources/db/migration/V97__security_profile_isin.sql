-- The ISIN every connector receives and every sync throws away.
--
-- Boursorama's search resolves an ISIN to the same symbol as the ticker
-- (LU1681043599 -> /cours/1rTCW8/, identical to querying CW8), so the composition provider
-- already works with one. What broke the ETF look-through is that OpenFigiIsinConverter.pickBest
-- prefers US OTC listings for non-US ISINs, handing Boursorama tickers it cannot find, while the
-- identifier that would have worked was discarded at ingestion. Keeping it repairs the existing
-- provider; it is also what a fee/KID lookup would be keyed on later.
--
-- On security_profile rather than account_holding: every sync deletes an account's holdings and
-- re-inserts them, so a column there would survive only until the next sync -- the same reason
-- holding_classification was deliberately kept off that table. security_profile is already
-- global, already keyed on the ticker every reader uses.
ALTER TABLE security_profile ADD COLUMN isin VARCHAR(12);

-- Deliberately NOT unique: a bad OpenFIGI pick can map two tickers onto one ISIN, and a
-- constraint violation here would fail a whole sync over reference data nobody asked for.
ALTER TABLE security_profile
    ADD CONSTRAINT ck_security_profile_isin
        CHECK (isin IS NULL OR isin ~ '^[A-Z]{2}[A-Z0-9]{9}[0-9]$');

CREATE INDEX idx_security_profile_isin ON security_profile(isin) WHERE isin IS NOT NULL;

-- A sync now seeds rows that carry an ISIN and nothing else. NULL is precisely what
-- SecurityProfileService.refreshStale already reads as "due"; leaving the column NOT NULL would
-- force a sentinel timestamp, which the 30-day cutoff would then read as freshly resolved.
ALTER TABLE security_profile ALTER COLUMN refreshed_at DROP NOT NULL;
