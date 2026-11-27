-- Typed error code explaining why an Enable Banking requisition failed or needs user action
-- (e.g. ENABLE_BANKING_NO_ACCOUNTS when an AUTHORIZED session shares zero accounts).
-- IF NOT EXISTS keeps the migration replayable if its version number has to change before merge.
ALTER TABLE requisition ADD COLUMN IF NOT EXISTS error_code VARCHAR(64);
