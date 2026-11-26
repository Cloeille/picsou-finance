-- Typed error code on Enable Banking requisitions (PostgreSQL V158__requisition_error_code).
ALTER TABLE requisition ADD COLUMN error_code TEXT;
