-- V77: merge the duplicate accounts a missing guard created.
--
-- WalletSyncService.resolveAccount was the one connector that did not check
-- existsSoftDeletedByExternalAccountIdAndMemberId before inserting, so every scheduled resync
-- of a wallet whose account the user had deleted inserted a brand new row for the same
-- external_account_id -- one duplicate per deletion, each starting its balance history over.
-- The guard is now in place; this repairs the rows already written.
--
-- Scope is deliberately narrow: only synced accounts (is_manual = false) sharing one
-- external_account_id AND one provider within one member. external_account_id is free text on
-- manual accounts (see V75), so manual rows are never touched, whatever they happen to contain.
--
-- provider is part of the key because an Enable Banking external id is the bank's own opaque
-- string: two institutions are free to hand out the same one, and merging accounts held at
-- different banks would destroy real data. Every connector writes a provider that is stable
-- across the duplicates it creates (a wallet's native ticker, an exchange name, the institution
-- name), so this cannot split a group that should merge -- unless the user edited provider on
-- some duplicates only, which merely leaves duplicates behind. Failing to merge is recoverable;
-- merging two different banks is not.
--
-- Survivor: the live row when there is one, else the most recently created. Everything the
-- losers own is re-pointed at it, then the loser rows go.

CREATE TEMP TABLE account_merge_map ON COMMIT DROP AS
WITH duplicated AS (
    SELECT member_id, external_account_id, provider
      FROM account
     WHERE external_account_id IS NOT NULL
       AND is_manual = false
     GROUP BY member_id, external_account_id, provider
    HAVING count(*) > 1
),
ranked AS (
    SELECT a.id,
           first_value(a.id) OVER w AS survivor_id,
           row_number()      OVER w AS rank
      FROM account a
      JOIN duplicated d
        ON d.member_id = a.member_id
       AND d.external_account_id = a.external_account_id
       AND d.provider IS NOT DISTINCT FROM a.provider
    WINDOW w AS (
        PARTITION BY a.member_id, a.external_account_id, a.provider
        -- Live row first; among equals, the most recent. id breaks the remaining ties so the
        -- ordering is total and the result reproducible.
        ORDER BY (a.deleted_at IS NULL) DESC, a.created_at DESC, a.id DESC
    )
)
SELECT id AS loser_id, survivor_id, rank
  FROM ranked
 WHERE id <> survivor_id;

CREATE INDEX ON account_merge_map(loser_id);

-- balance_snapshot -- UNIQUE (account_id, date)
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

-- account_holding -- UNIQUE (account_id, ticker)
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

-- account_ownership -- UNIQUE (account_id, member_id), plus a per-row CHECK bounding each share
-- to (0, 100]. Nothing constrains the *sum*, which is why these rows are taken as a whole set
-- and never unioned: 60% held by A on the survivor and 60% held by B on a loser are two
-- descriptions of one account, not a 120% one, and merging them row by row would silently
-- produce that. The survivor's own set wins when it has one; otherwise the best-ranked loser's
-- set is adopted entire.
DELETE FROM account_ownership o USING account_merge_map m
 WHERE o.account_id = m.loser_id
   AND (EXISTS (SELECT 1 FROM account_ownership k WHERE k.account_id = m.survivor_id)
     OR EXISTS (SELECT 1 FROM account_ownership k JOIN account_merge_map n ON n.loser_id = k.account_id
                 WHERE n.survivor_id = m.survivor_id AND n.rank < m.rank));
UPDATE account_ownership o SET account_id = m.survivor_id
  FROM account_merge_map m WHERE o.account_id = m.loser_id;

-- crypto_exchange_position -- UNIQUE (account_id, product, ticker)
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

-- goal_account -- PRIMARY KEY (goal_id, account_id). A goal already linked to the survivor
-- simply keeps that link; the duplicate one is redundant, not information.
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

-- property_valuation -- UNIQUE (account_id, valued_at)
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

-- real_estate_metadata and debt -- UNIQUE (account_id), one row describing the whole account.
-- Only manual account types carry these and this migration excludes manual rows, so in practice
-- there is nothing to move. They are handled anyway because "in practice" is doing real work in
-- that sentence: an account's type is editable, so a synced row retyped to REAL_ESTATE or LOAN
-- can acquire one. Leaving them out would abort the whole migration on a foreign key, and a
-- migration that fails is an instance that will not boot. Same set rule as ownership: the
-- survivor's row wins, else the best-ranked loser's.
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

-- debt.linked_account_id is ON DELETE SET NULL and carries no uniqueness: re-point it so a loan
-- pointing at a duplicate keeps pointing at the account that survived, instead of silently
-- losing the link when the row goes.
UPDATE debt d SET linked_account_id = m.survivor_id
  FROM account_merge_map m WHERE d.linked_account_id = m.loser_id;

-- transaction -- no uniqueness to reconcile, every row moves.
UPDATE transaction t SET account_id = m.survivor_id
  FROM account_merge_map m WHERE t.account_id = m.loser_id;

DELETE FROM account a USING account_merge_map m WHERE a.id = m.loser_id;
