-- Distinguish hand-entered statements from rebuilt history in balance_snapshot.
--
-- A manual ledger account can carry month-end statements typed by hand alongside a
-- partial transaction history. Rebuilds must preserve the statements and only
-- recompute the derived rows, so the origin has to live on the row itself: neither
-- the balance nor invested_amount tells them apart (cash costs what it is worth).
-- DERIVED stays the column default: an unknown row is rebuilt, never wrongly
-- protected. Existing rows are therefore all DERIVED; statements entered after this
-- migration are marked MANUAL on write, and re-entering an older statement flips it.
ALTER TABLE balance_snapshot ADD COLUMN source VARCHAR(16) NOT NULL DEFAULT 'DERIVED';

ALTER TABLE balance_snapshot
    ADD CONSTRAINT ck_balance_snapshot_source
        CHECK (source IN ('MANUAL', 'DERIVED'));

-- The statement's native figure, stored alongside its EUR conversion. A later rebuild
-- anchors the account balance on this exact figure instead of converting the EUR value
-- back at the current rate, which would drift with FX movements. NULL for rows written
-- before this migration; the anchor falls back to the EUR conversion then.
ALTER TABLE balance_snapshot ADD COLUMN native_balance DECIMAL(20, 8);

-- When a hand-entered figure was typed, distinct from the row creation time: a DERIVED
-- today row recycled into a statement keeps its old created_at, which would misorder
-- same-day movements. NULL for every DERIVED row.
ALTER TABLE balance_snapshot ADD COLUMN statement_entered_at TIMESTAMPTZ;
