-- The instrument's own identity on each holding (#699, part of #698). Mirrors PostgreSQL V156:
-- a nullable ISIN, a check on its shape (GLOB is case-sensitive), a partial index, and the
-- same two backfills (the ticker when it is an ISIN, then the ISIN security_profile holds for it).
ALTER TABLE account_holding ADD COLUMN isin TEXT
    CONSTRAINT ck_account_holding_isin CHECK (
        isin IS NULL OR (
            length(isin) = 12
            AND isin GLOB '[A-Z][A-Z][A-Z0-9][A-Z0-9][A-Z0-9][A-Z0-9][A-Z0-9][A-Z0-9][A-Z0-9][A-Z0-9][A-Z0-9][0-9]'
        )
    );

CREATE INDEX IF NOT EXISTS idx_account_holding_isin ON account_holding(isin) WHERE isin IS NOT NULL;

UPDATE account_holding
SET isin = UPPER(TRIM(ticker))
WHERE isin IS NULL
  AND length(UPPER(TRIM(ticker))) = 12
  AND UPPER(TRIM(ticker)) GLOB '[A-Z][A-Z][A-Z0-9][A-Z0-9][A-Z0-9][A-Z0-9][A-Z0-9][A-Z0-9][A-Z0-9][A-Z0-9][A-Z0-9][0-9]';

UPDATE account_holding
SET isin = (
    SELECT p.isin FROM security_profile p
    WHERE p.isin IS NOT NULL AND p.ticker = UPPER(account_holding.ticker)
    ORDER BY p.id LIMIT 1
)
WHERE isin IS NULL
  AND EXISTS (
    SELECT 1 FROM security_profile p
    WHERE p.isin IS NOT NULL AND p.ticker = UPPER(account_holding.ticker)
  );
