-- Revolut pockets: link a pocket sub-account to its parent wallet.
-- NULL for normal accounts; set to the parent wallet id for Revolut pockets.
ALTER TABLE account ADD COLUMN parent_account_id BIGINT NULL
    REFERENCES account(id) ON DELETE SET NULL;

CREATE INDEX idx_account_parent_account_id ON account(parent_account_id)
    WHERE parent_account_id IS NOT NULL;
