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
--     category flag, no recurring series, no AI category suggestion. A copy someone categorised, or
--     that the AI already looked at, is left for them to merge;
--   * the removed row must come from a LATER sync than the kept one (`created_at` more than one
--     hour after it). A relabelled copy always lands in a sync after the row it replaces; two
--     genuine same-day purchases at one merchant ("AMZN MKTP FR*1A2B" / "AMZN MKTP FR*3C4D",
--     "SNCF" / "SNCF CONNECT") land in the same sync, with created_at within seconds, and stay.
--     `transaction.created_at` is TIMESTAMPTZ NOT NULL DEFAULT NOW() (V12), and the JPA entity
--     fills it with the insert time (Transaction.createdAt), so it records the sync that stored
--     the row.
--
-- Nothing is lost for good: the removed rows are copied, in the same statement, to the table
-- `amex_v117_removed` (same columns as `transaction`, no constraints). To restore one:
--     INSERT INTO transaction SELECT * FROM amex_v117_removed WHERE id = <id>;
-- Drop the table once the result has been checked: DROP TABLE amex_v117_removed;
-- The only table that references `transaction` is `ai_call_log` (V42, ON DELETE SET NULL), so the
-- delete cascades nowhere and is blocked by nothing.
--
-- Idempotent: once a group is down to one row it no longer matches, and the backup table is only
-- created when missing, so re-running the script fails on nothing and deletes nothing more.

CREATE TABLE IF NOT EXISTS amex_v117_removed AS SELECT * FROM transaction WITH NO DATA;

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
),
removed AS (
    DELETE FROM transaction dup
    USING pairs p, transaction kept
    WHERE dup.id = p.dup_id
      AND kept.id = p.kept_id
      AND dup.description <> kept.description
      AND dup.created_at > kept.created_at + interval '1 hour'
      AND dup.category_id IS NULL
      AND dup.category IS NULL
      AND dup.category_manual = FALSE
      AND dup.recurring_series_id IS NULL
      AND dup.ai_suggested_category_id IS NULL
    RETURNING dup.*
)
INSERT INTO amex_v117_removed SELECT * FROM removed;
