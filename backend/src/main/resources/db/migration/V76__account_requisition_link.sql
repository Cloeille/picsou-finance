-- V76: Record which Enable Banking connection an account came from.
--
-- Deleting an account now removes the connection behind it once no live account is left on
-- that connection (see the account-deletion ADR). Every other provider can be recognised from
-- external_account_id alone -- the namespaces are disjoint ('wallet_', 'crypto_exchange_',
-- 'amundi_', 'tr_', 'bd_', 'ibkr_', 'degiro-portfolio') -- but an Enable Banking account holds
-- the bank's own opaque id, which says nothing about the requisition that produced it.
-- SyncService.upsertAccount has the requisition in hand and until now persisted only its name,
-- so the link existed at write time and was thrown away.
--
-- ON DELETE SET NULL, not CASCADE: removing a connection must never take the account rows (and
-- their balance history) with it. The account survives, orphaned, exactly as it does today.
ALTER TABLE account ADD COLUMN requisition_id BIGINT REFERENCES requisition(id) ON DELETE SET NULL;

CREATE INDEX idx_account_requisition ON account(requisition_id) WHERE requisition_id IS NOT NULL;

-- Backfill for accounts linked before this column existed. provider is set to the institution
-- name at creation, which is the only trace of the origin left on the row, so that is what we
-- match on -- deliberately best-effort:
--
--   * skipped when a member holds several requisitions for the same institution (the last
--     subquery), because the name alone cannot say which one; those rows stay NULL and their
--     connection is simply never auto-removed, which is the safe direction to fail;
--   * restricted to synced accounts whose external id sits in no known namespace, so a manual
--     account the user happened to name after their bank is not swept in.
--
-- Guarded on IS NULL so a replay cannot overwrite a link written since by upsertAccount.
UPDATE account a
   SET requisition_id = r.id
  FROM requisition r
 WHERE r.member_id = a.member_id
   AND r.institution_name = a.provider
   AND a.requisition_id IS NULL
   AND a.is_manual = false
   AND a.external_account_id IS NOT NULL
   AND a.external_account_id NOT LIKE 'wallet\_%'
   AND a.external_account_id NOT LIKE 'crypto\_exchange\_%'
   AND a.external_account_id NOT LIKE 'amundi\_%'
   AND a.external_account_id NOT LIKE 'tr\_%'
   AND a.external_account_id NOT LIKE 'bd\_%'
   AND a.external_account_id NOT LIKE 'ibkr\_%'
   AND a.external_account_id <> 'degiro-portfolio'
   AND (SELECT count(*) FROM requisition r2
         WHERE r2.member_id = a.member_id
           AND r2.institution_name = a.provider) = 1;
