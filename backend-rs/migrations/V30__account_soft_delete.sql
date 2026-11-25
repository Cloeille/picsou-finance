-- Soft-delete support for accounts.
-- A non-null deleted_at means the account is hidden from every JPA query
-- (enforced by @SQLRestriction on the entity) and must NOT be resurrected
-- by sync upserts that key on (external_account_id, member_id).
ALTER TABLE account
    ADD COLUMN deleted_at TIMESTAMP NULL;

CREATE INDEX idx_account_deleted_at ON account (deleted_at);
