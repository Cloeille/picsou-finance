-- V118: Remove Revolut exchange legs stored with the wrong currency and scale (GH issue #251).
--
-- Until #251 the sidecar divided every amount by 100 and the importer stamped every row with
-- its pocket's currency. The foreign leg of a currency exchange ("Exchanged to EUR" from a
-- crypto asset) was therefore stored as EUR, far too large, and inflated the essential-expense
-- estimate, cashflow and budget. Balances and net worth never read these rows.
--
-- Both legs of a Revolut exchange share one provider id. A pair is two Revolut rows of one
-- member, on different accounts, with that id, opposite signs and magnitudes at least 1000x
-- apart. A same-currency transfer has equal magnitudes and is left alone. Of the two legs the larger
-- one is the mis-scaled leg: a crypto amount divided by 100 instead of its own precision
-- always exceeds its euro value. The correct EUR leg is kept.
--
-- The row is deleted, not rewritten: its real euro value cannot be rebuilt from the database.
-- The fixed importer drops non-EUR legs, so a later sync does not bring it back.
-- docker/deploy.sh takes a pg_dump before any migration runs.

WITH revolut_leg AS (
    SELECT t.id, t.account_id, t.external_id, t.amount, a.member_id
    FROM transaction t
    JOIN account a ON a.id = t.account_id
    WHERE t.external_transaction_id LIKE 'revolut:%'
      AND t.external_id IS NOT NULL
      AND t.is_manual = FALSE
),
pair AS (
    SELECT member_id, external_id
    FROM revolut_leg
    GROUP BY member_id, external_id
    HAVING COUNT(*) = 2
       AND COUNT(DISTINCT account_id) = 2
       AND MIN(amount) < 0
       AND MAX(amount) > 0
       -- The reported legs are 10^7 to 10^8 apart. A factor of 1000 leaves any plausible
       -- fiat rate, fee or rounding difference untouched.
       AND MAX(ABS(amount)) > 1000 * MIN(ABS(amount))
),
mis_scaled AS (
    SELECT DISTINCT ON (l.member_id, l.external_id) l.id
    FROM revolut_leg l
    JOIN pair p ON p.member_id IS NOT DISTINCT FROM l.member_id AND p.external_id = l.external_id
    ORDER BY l.member_id, l.external_id, ABS(l.amount) DESC
)
DELETE FROM transaction WHERE id IN (SELECT id FROM mis_scaled);
