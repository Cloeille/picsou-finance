-- Deleting an account is a soft-delete (account.deleted_at), so the ON DELETE SET NULL of
-- debt.linked_account_id and peer_loan_terms.linked_account_id never fired. A loan still
-- linked to a deleted account (a property sold and removed, say) then failed to load, and
-- the pages listing it answered 500. AccountService#delete now unlinks them; this repairs
-- the rows left behind before that.
UPDATE debt SET linked_account_id = NULL
 WHERE linked_account_id IN (SELECT id FROM account WHERE deleted_at IS NOT NULL);

UPDATE peer_loan_terms SET linked_account_id = NULL
 WHERE linked_account_id IN (SELECT id FROM account WHERE deleted_at IS NOT NULL);
