-- V117: Remove the copies an American Express re-sync made of already-stored charges (GH issue #245).
--
-- AMEX re-describes a charge between two syncs ("ANTHROPIC PBC" becomes "ANTHROPIC* CLAUDE SUB
-- SAN FRANCISCO"). A derived external id hashes the label, so the charge came back under an id
-- nothing stored held and was inserted beside the row it replaces. The copy has no category, so a
-- budget counted the spend twice. The sync now re-keys the stored row instead; this removes the
-- copies the old code already made.
--
-- Deliberately narrow, because a wrong delete loses a real purchase:
--   * American Express credit cards only, and only posted provider rows (external id `amex_tx_...`):
--     manual rows never match, and a pending row (`amex_txp_...`) is removed by the sync's own
--     pending lifecycle, which must not lose its posted counterpart here;
--   * a group is the same account, date, amount and first word of the label, and it is touched
--     only when it holds EXACTLY two rows with different labels: the older row is kept, the newer
--     one removed. Three or more rows could hold a genuine second purchase, and two rows with the
--     same label are what two identical purchases look like, so both are left alone;
--   * the removed row must carry nothing of the user's: no category (managed or legacy), no manual
--     category flag, no recurring series. A copy someone categorised is left for them to merge.
-- Idempotent: once a group is down to one row it no longer matches.

WITH amex_rows AS (
    SELECT t.id,
           t.account_id,
           t.date,
           t.amount,
           lower(substring(t.description FROM '[[:alnum:]]+')) AS first_word
    FROM transaction t
    JOIN account a ON a.id = t.account_id
    WHERE a.provider = 'American Express'
      AND a.type = 'CREDIT_CARD'
      AND t.is_manual = FALSE
      AND t.external_id LIKE 'amex\_tx\_%'
),
pairs AS (
    SELECT min(id) AS kept_id,
           max(id) AS dup_id
    FROM amex_rows
    WHERE first_word IS NOT NULL
    GROUP BY account_id, date, amount, first_word
    HAVING count(*) = 2
)
DELETE FROM transaction dup
USING pairs p, transaction kept
WHERE dup.id = p.dup_id
  AND kept.id = p.kept_id
  AND dup.description <> kept.description
  AND dup.category_id IS NULL
  AND dup.category IS NULL
  AND dup.category_manual = FALSE
  AND dup.recurring_series_id IS NULL;
