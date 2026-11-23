-- V50: Add bank logo URL to account and requisition tables
-- Renumbered from V38 to avoid a Flyway version collision with the 1.1.0
-- branch, which already owns V33-V47 (see V38__budget_categorization_foundation.sql there).

ALTER TABLE account ADD COLUMN logo_url TEXT;
ALTER TABLE requisition ADD COLUMN logo_url TEXT;
ALTER TABLE requisition ADD COLUMN logo_backfill_attempted_at TIMESTAMPTZ;
