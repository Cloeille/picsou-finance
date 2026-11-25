-- Track invested capital (cost basis) alongside market value in balance snapshots.
-- Enables the dashboard chart to show total vs capital invested (PnL).

ALTER TABLE balance_snapshot ADD COLUMN invested_amount DECIMAL(20, 8) NOT NULL DEFAULT 0;

-- Backfill: for existing snapshots, invested = balance (no holdings PnL data in history)
UPDATE balance_snapshot SET invested_amount = balance WHERE invested_amount = 0;

-- Remove the default — future inserts must provide the value explicitly
ALTER TABLE balance_snapshot ALTER COLUMN invested_amount DROP DEFAULT;
