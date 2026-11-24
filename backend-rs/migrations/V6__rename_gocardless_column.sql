-- V6: rename gocardless_account_id → external_account_id
ALTER TABLE account RENAME COLUMN gocardless_account_id TO external_account_id;
DROP INDEX idx_account_gocardless_id;
CREATE INDEX idx_account_external_id ON account(external_account_id) WHERE external_account_id IS NOT NULL;
