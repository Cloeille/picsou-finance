-- V47: Prevent duplicate active synced accounts with the same external_account_id + member_id + provider.
-- Without this, concurrent TR sync calls (background + manual) could both find no existing account
-- and both INSERT a new row for the same externalId, producing x2 on the dashboard.
--
-- A 1.0.x database runs this migration out of order on its first 1.1.0 start (#265), with its
-- accounts in place. The key is the one V72 settled on and V77 merges by: an Enable Banking
-- external id is the bank's own opaque string, so two banks may share one, and external_account_id
-- is free text on manual accounts. Genuine duplicates are merged into the highest id, by V77's
-- rules, so their history moves instead of cascading away with the deleted row.
-- 1.1.0 databases applied an earlier version of this file: LegacyMigrationRenumberingCallback.
--
-- Not merged here: transaction.external_id (V33), account.parent_account_id (V43) and
-- savings_interest_config (V44) are created just before this migration and are empty whenever it
-- finds accounts to merge.

CREATE TEMP TABLE account_merge_map ON COMMIT DROP AS
SELECT id AS loser_id, survivor_id, rank
  FROM (SELECT id,
               first_value(id) OVER w AS survivor_id,
               row_number()    OVER w AS rank
          FROM account
         WHERE deleted_at IS NULL
           AND external_account_id IS NOT NULL
           AND is_manual = false
        WINDOW w AS (PARTITION BY member_id, external_account_id, provider ORDER BY id DESC)) ranked
 WHERE id <> survivor_id;

DELETE FROM balance_snapshot s USING account_merge_map m
 WHERE s.account_id = m.loser_id
   AND EXISTS (SELECT 1 FROM balance_snapshot k
                WHERE k.account_id = m.survivor_id AND k.date = s.date);
DELETE FROM balance_snapshot s USING account_merge_map m
 WHERE s.account_id = m.loser_id
   AND EXISTS (SELECT 1 FROM balance_snapshot k JOIN account_merge_map n ON n.loser_id = k.account_id
                WHERE n.survivor_id = m.survivor_id AND k.date = s.date
                  AND (n.rank, k.id) < (m.rank, s.id));
UPDATE balance_snapshot s SET account_id = m.survivor_id
  FROM account_merge_map m WHERE s.account_id = m.loser_id;

DELETE FROM account_holding h USING account_merge_map m
 WHERE h.account_id = m.loser_id
   AND EXISTS (SELECT 1 FROM account_holding k
                WHERE k.account_id = m.survivor_id AND k.ticker = h.ticker);
DELETE FROM account_holding h USING account_merge_map m
 WHERE h.account_id = m.loser_id
   AND EXISTS (SELECT 1 FROM account_holding k JOIN account_merge_map n ON n.loser_id = k.account_id
                WHERE n.survivor_id = m.survivor_id AND k.ticker = h.ticker
                  AND (n.rank, k.id) < (m.rank, h.id));
UPDATE account_holding h SET account_id = m.survivor_id
  FROM account_merge_map m WHERE h.account_id = m.loser_id;

DELETE FROM goal_account g USING account_merge_map m
 WHERE g.account_id = m.loser_id
   AND EXISTS (SELECT 1 FROM goal_account k
                WHERE k.account_id = m.survivor_id AND k.goal_id = g.goal_id);
DELETE FROM goal_account g USING account_merge_map m
 WHERE g.account_id = m.loser_id
   AND EXISTS (SELECT 1 FROM goal_account k JOIN account_merge_map n ON n.loser_id = k.account_id
                WHERE n.survivor_id = m.survivor_id AND k.goal_id = g.goal_id
                  AND n.rank < m.rank);
UPDATE goal_account g SET account_id = m.survivor_id
  FROM account_merge_map m WHERE g.account_id = m.loser_id;

DELETE FROM real_estate_metadata r USING account_merge_map m
 WHERE r.account_id = m.loser_id
   AND (EXISTS (SELECT 1 FROM real_estate_metadata k WHERE k.account_id = m.survivor_id)
     OR EXISTS (SELECT 1 FROM real_estate_metadata k JOIN account_merge_map n ON n.loser_id = k.account_id
                 WHERE n.survivor_id = m.survivor_id AND n.rank < m.rank));
UPDATE real_estate_metadata r SET account_id = m.survivor_id
  FROM account_merge_map m WHERE r.account_id = m.loser_id;

DELETE FROM debt d USING account_merge_map m
 WHERE d.account_id = m.loser_id
   AND (EXISTS (SELECT 1 FROM debt k WHERE k.account_id = m.survivor_id)
     OR EXISTS (SELECT 1 FROM debt k JOIN account_merge_map n ON n.loser_id = k.account_id
                 WHERE n.survivor_id = m.survivor_id AND n.rank < m.rank));
UPDATE debt d SET account_id = m.survivor_id
  FROM account_merge_map m WHERE d.account_id = m.loser_id;
UPDATE debt d SET linked_account_id = m.survivor_id
  FROM account_merge_map m WHERE d.linked_account_id = m.loser_id;

UPDATE transaction t SET account_id = m.survivor_id
  FROM account_merge_map m WHERE t.account_id = m.loser_id;

-- resource_id has no foreign key: a MANUAL share of a loser would point at nothing.
DELETE FROM shared_resource r USING account_merge_map m
 WHERE r.resource_type = 'ACCOUNT' AND r.resource_id = m.loser_id
   AND EXISTS (SELECT 1 FROM shared_resource k
                WHERE k.owner_member_id = r.owner_member_id AND k.resource_type = 'ACCOUNT'
                  AND k.resource_id = m.survivor_id);
DELETE FROM shared_resource r USING account_merge_map m
 WHERE r.resource_type = 'ACCOUNT' AND r.resource_id = m.loser_id
   AND EXISTS (SELECT 1 FROM shared_resource k JOIN account_merge_map n ON n.loser_id = k.resource_id
                WHERE k.owner_member_id = r.owner_member_id AND k.resource_type = 'ACCOUNT'
                  AND n.survivor_id = m.survivor_id AND n.rank < m.rank);
UPDATE shared_resource r SET resource_id = m.survivor_id
  FROM account_merge_map m WHERE r.resource_type = 'ACCOUNT' AND r.resource_id = m.loser_id;

-- The 1.0.x line created these tables in V66 and V74, so they exist here only on a 1.0.x
-- database. PL/pgSQL resolves a table when the statement first runs, not before.
DO $$
BEGIN
    IF to_regclass('account_ownership') IS NOT NULL THEN
        DELETE FROM account_ownership o USING account_merge_map m
         WHERE o.account_id = m.loser_id
           AND (EXISTS (SELECT 1 FROM account_ownership k WHERE k.account_id = m.survivor_id)
             OR EXISTS (SELECT 1 FROM account_ownership k JOIN account_merge_map n ON n.loser_id = k.account_id
                         WHERE n.survivor_id = m.survivor_id AND n.rank < m.rank));
        UPDATE account_ownership o SET account_id = m.survivor_id
          FROM account_merge_map m WHERE o.account_id = m.loser_id;
    END IF;

    IF to_regclass('property_valuation') IS NOT NULL THEN
        DELETE FROM property_valuation p USING account_merge_map m
         WHERE p.account_id = m.loser_id
           AND EXISTS (SELECT 1 FROM property_valuation k
                        WHERE k.account_id = m.survivor_id AND k.valued_at = p.valued_at);
        DELETE FROM property_valuation p USING account_merge_map m
         WHERE p.account_id = m.loser_id
           AND EXISTS (SELECT 1 FROM property_valuation k JOIN account_merge_map n ON n.loser_id = k.account_id
                        WHERE n.survivor_id = m.survivor_id AND k.valued_at = p.valued_at
                          AND (n.rank, k.id) < (m.rank, p.id));
        UPDATE property_valuation p SET account_id = m.survivor_id
          FROM account_merge_map m WHERE p.account_id = m.loser_id;
    END IF;

    IF to_regclass('crypto_exchange_position') IS NOT NULL THEN
        DELETE FROM crypto_exchange_position p USING account_merge_map m
         WHERE p.account_id = m.loser_id
           AND EXISTS (SELECT 1 FROM crypto_exchange_position k
                        WHERE k.account_id = m.survivor_id AND k.product = p.product AND k.ticker = p.ticker);
        DELETE FROM crypto_exchange_position p USING account_merge_map m
         WHERE p.account_id = m.loser_id
           AND EXISTS (SELECT 1 FROM crypto_exchange_position k JOIN account_merge_map n ON n.loser_id = k.account_id
                        WHERE n.survivor_id = m.survivor_id AND k.product = p.product AND k.ticker = p.ticker
                          AND (n.rank, k.id) < (m.rank, p.id));
        UPDATE crypto_exchange_position p SET account_id = m.survivor_id
          FROM account_merge_map m WHERE p.account_id = m.loser_id;
    END IF;
END $$;

DELETE FROM account a USING account_merge_map m WHERE a.id = m.loser_id;

-- Partial unique index: only constrains active synced accounts. Soft-deleted tombstones
-- (deleted_at IS NOT NULL) are excluded so the same externalId can be reused when the user
-- reconnects after an explicit deletion.
CREATE UNIQUE INDEX account_external_unique_active
  ON account(external_account_id, member_id, provider)
  WHERE deleted_at IS NULL AND external_account_id IS NOT NULL AND is_manual = false;
