-- Finary (and similar aggregators) can return instrument codes longer than 30
-- characters (RealT property tokens, long slugs). Holdings would otherwise fail
-- the unique (account_id, ticker) insert.
ALTER TABLE account_holding
    ALTER COLUMN ticker TYPE VARCHAR(100);

ALTER TABLE account_holding
    ALTER COLUMN name TYPE VARCHAR(255);
