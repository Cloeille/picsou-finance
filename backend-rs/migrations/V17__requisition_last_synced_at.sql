-- V17: Add last_synced_at to requisition table

ALTER TABLE requisition ADD COLUMN last_synced_at TIMESTAMPTZ;
