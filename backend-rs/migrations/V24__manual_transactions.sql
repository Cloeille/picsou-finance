ALTER TABLE transaction
  ADD COLUMN is_manual      BOOLEAN       NOT NULL DEFAULT FALSE,
  ADD COLUMN tx_type        VARCHAR(20)   NULL,
  ADD COLUMN ticker         VARCHAR(30)   NULL,
  ADD COLUMN quantity       NUMERIC(20,8) NULL,
  ADD COLUMN price_per_unit NUMERIC(20,8) NULL;

CREATE INDEX idx_transaction_account_manual ON transaction(account_id, is_manual);
