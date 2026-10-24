-- V118: Remove Revolut exchange legs stored with the wrong currency and scale (GH issue #251).
--
-- Until #251 the sidecar divided every amount by 100 and the importer stamped every row with
-- its pocket's currency. The foreign leg of a currency exchange ("Exchanged to EUR" from a
-- crypto asset) was therefore stored as EUR at the wrong scale, and inflated the
-- essential-expense estimate, cashflow and budget. Balances and net worth never read these rows.
--
-- Both legs of a Revolut exchange share one provider id. A pair is two Revolut rows of one
-- member, on different accounts, with that id and opposite signs. Each leg's description names
-- the other side: the EUR leg reads "Exchanged from BTC" or "Exchanged to BTC", the foreign leg
-- "Exchanged to EUR" or "Exchanged from EUR". The leg that names EUR is the foreign one, whatever
-- the asset's price. A magnitude ratio cannot tell them apart: the gap is about 1e6 / price,
-- only ~10x for BTC. A pair where both or neither leg names EUR is left alone.
--
-- The row is deleted, not rewritten: its real euro value cannot be rebuilt from the database.
-- A manual category on it goes with it. The fixed importer drops non-EUR legs, so a later sync
-- does not bring it back. Recurring series are not recomputed here; the next detection run does it.
-- docker/deploy.sh takes a pg_dump before any migration runs; other deployments should take one.

WITH revolut_leg AS (
    SELECT t.id, t.account_id, t.external_id, t.amount, a.member_id,
           btrim(t.description) ~* '^exchanged (to|from) eur$' AS names_eur
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
       AND COUNT(*) FILTER (WHERE names_eur) = 1
)
DELETE FROM transaction
WHERE id IN (
    SELECT l.id
    FROM revolut_leg l
    JOIN pair p ON p.member_id IS NOT DISTINCT FROM l.member_id AND p.external_id = l.external_id
    WHERE l.names_eur
);
