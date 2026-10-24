-- V118: Repair the foreign leg of Revolut currency exchanges stored with the wrong currency
-- and scale (GH issue #251).
--
-- Until #251 the sidecar divided every amount by 100 and the importer stamped every row with
-- its pocket's currency. The foreign leg of an exchange (for example a crypto asset sold for
-- EUR) was stored as EUR at the wrong scale and inflated the essential-expense estimate,
-- cashflow and budget. Balances and net worth never read these rows.
--
-- The rule reads one row at a time, from its wording and sign. "Exchanged to EUR" means EUR
-- was received, so the EUR leg is the credit and a debit reading that is the foreign leg.
-- "Exchanged from EUR" means EUR was spent, so the EUR leg is the debit and a credit reading
-- that is the foreign leg. A row is deleted when all of these hold:
--   - its account is a Revolut account (soft-deleted accounts included);
--   - it is not manual and has an external_id;
--   - it is a debit reading "Exchanged to EUR" or a credit reading "Exchanged from EUR"
--     (case, padding and repeated spaces ignored);
--   - another Revolut account of the same member holds a row with the same external_id and
--     the opposite sign: the EUR leg, which stays.
-- It works whether Revolut gives each leg its own description or both the same one.
--
-- Why not the amounts: the gap between the legs is about 1e6 / price, only ~10x for BTC, so
-- no ratio fits every asset. Why not external_transaction_id or "exactly two rows": legacy
-- rows have no external_transaction_id, and an exchange can span duplicate Revolut accounts,
-- so it can hold three rows.
--
-- The row is deleted, not rewritten: its real euro value cannot be rebuilt from the database.
-- The fixed importer drops non-EUR legs, so a later sync does not bring it back.
--
-- Backup: the deleted rows are copied, in the same statement, to transaction_v118_backup (same
-- columns, but no defaults, constraints or indexes, so it does not depend on the live table),
-- so a manual category on them stays recoverable. To undo:
--   INSERT INTO transaction SELECT * FROM transaction_v118_backup;
-- Drop the table once the result has been checked.
--
-- Recurring series are not recomputed here; the next detection run (any sync, or
-- POST /api/recurring/detect) does it. docker/deploy.sh takes a pg_dump before any migration
-- runs; other deployments should take one.

CREATE TABLE IF NOT EXISTS transaction_v118_backup (LIKE transaction);

WITH repaired AS (
    DELETE FROM transaction t
    USING account a
    WHERE a.id = t.account_id
      AND a.provider = 'Revolut'
      AND t.is_manual = FALSE
      AND t.external_id IS NOT NULL
      AND (   (btrim(t.description) ~* '^exchanged\s+to\s+eur$'   AND t.amount < 0)
           OR (btrim(t.description) ~* '^exchanged\s+from\s+eur$' AND t.amount > 0))
      AND EXISTS (
          SELECT 1
          FROM transaction p
          JOIN account pa ON pa.id = p.account_id
          WHERE p.external_id = t.external_id
            AND p.account_id <> t.account_id
            AND pa.provider = 'Revolut'
            AND pa.member_id IS NOT DISTINCT FROM a.member_id
            AND sign(p.amount) = -sign(t.amount)
      )
    RETURNING t.*
)
INSERT INTO transaction_v118_backup
SELECT * FROM repaired;
