-- The instrument's own identity on each holding (#699, part of #698). A ticker names one listing,
-- and a wrong one can carry another market's price (#683: LU1829219390 priced through MULYF).
-- Pricing by ISIN needs the ISIN on the line itself: today it only lives in security_profile,
-- keyed by that same ticker, and only for the connectors that record it.
-- Nullable: crypto, manual symbols and broker-only lines have no ISIN.
-- IF NOT EXISTS keeps the migration replayable if its version number has to change before merge.
ALTER TABLE account_holding ADD COLUMN IF NOT EXISTS isin VARCHAR(12);

ALTER TABLE account_holding DROP CONSTRAINT IF EXISTS ck_account_holding_isin;
ALTER TABLE account_holding
    ADD CONSTRAINT ck_account_holding_isin
        CHECK (isin IS NULL OR isin ~ '^[A-Z]{2}[A-Z0-9]{9}[0-9]$');

-- Not unique: two lines of one account can hold the same instrument under two tickers until a
-- connector merges them, and that is not this migration's call.
CREATE INDEX IF NOT EXISTS idx_account_holding_isin ON account_holding(isin) WHERE isin IS NOT NULL;

-- Backfill 1: a ticker that is itself an ISIN (Amundi, CMES, Natixis use it as the key, and an
-- unresolved ISIN falls back to itself everywhere else). Crypto accounts and Trade Republic's
-- XF000 crypto pseudo-ISINs are excluded.
UPDATE account_holding h
SET isin = UPPER(TRIM(h.ticker))
FROM account a
WHERE a.id = h.account_id
  AND a.type <> 'CRYPTO'
  AND h.isin IS NULL
  AND UPPER(TRIM(h.ticker)) ~ '^[A-Z]{2}[A-Z0-9]{9}[0-9]$'
  AND UPPER(TRIM(h.ticker)) NOT LIKE 'XF000%';

-- Backfill 2: the ISIN a connector already recorded for this ticker. security_profile stores
-- tickers upper-cased. Crypto accounts and Trade Republic's XF000 crypto pseudo-ISINs are excluded.
UPDATE account_holding h
SET isin = p.isin
FROM account a, security_profile p
WHERE a.id = h.account_id
  AND a.type <> 'CRYPTO'
  AND h.isin IS NULL
  AND p.isin IS NOT NULL
  AND p.isin NOT LIKE 'XF000%'
  AND p.ticker = UPPER(TRIM(h.ticker));
