-- Recurring detection v2 (1.1.0, milestone M3).
-- The detector is rewritten around a stable merchant-label identity and now:
--   * scores each series (confidence) and records its amount envelope (min/max, variable);
--   * silently auto-confirms high-confidence series, with an activity trail + undo as the safety net;
--   * tracks price changes (previous_amount / price_changed_at);
--   * links the member's transactions back to their series (transaction.recurring_series_id).
-- New runtime statuses (LATE / DUE_SOON) are computed in the DTO, never stored — recurring_status
-- stays a native PG enum (append-only), untouched here.

ALTER TABLE recurring_series
  ADD COLUMN confidence       NUMERIC(4,3),                    -- 0.000–1.000; null for manually-declared series
  ADD COLUMN amount_min       NUMERIC(20,2),                   -- smallest observed occurrence (signed)
  ADD COLUMN amount_max       NUMERIC(20,2),                   -- largest observed occurrence (signed)
  ADD COLUMN is_variable      BOOLEAN NOT NULL DEFAULT FALSE,  -- amount drifts each period (e.g. utility bill)
  ADD COLUMN previous_amount  NUMERIC(20,2),                   -- expected_amount before the last detected change
  ADD COLUMN price_changed_at DATE,                            -- when expected_amount last moved
  ADD COLUMN auto_confirmed   BOOLEAN NOT NULL DEFAULT FALSE;  -- system confirmed silently (vs user-confirmed)

-- Stable identity: one series per member per clean merchant label, case-insensitive. Replaces the
-- old raw-counterparty key. lower(label) so "Netflix" and "NETFLIX" never split into two series.
CREATE UNIQUE INDEX idx_recurring_member_label ON recurring_series (member_id, lower(label));

-- recurring_series_id already exists on transaction (FK from V35, ON DELETE SET NULL). Detection now
-- populates it, so index it for the series → member-transactions lookup.
CREATE INDEX idx_transaction_recurring_series ON transaction (recurring_series_id);
