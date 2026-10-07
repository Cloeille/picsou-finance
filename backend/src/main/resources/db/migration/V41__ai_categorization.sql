-- Optional AI categorization (1.1.0). The deterministic pipeline (rules + brand KB) is
-- unchanged and stays the high-precision first pass; an opt-in LLM categorizer handles
-- only the long tail of merchants it leaves uncategorized. Everything here defaults OFF,
-- so existing instances behave exactly as before until a member turns it on.

-- ── Per-member AI settings ─────────────────────────────────────────────────
-- `ai_categorization_enabled` is the master opt-in (OFF by default).
-- `ai_mode` is how a suggestion is applied: SUGGEST (inbox only), AUTO_HIGH_CONFIDENCE
--   (auto-apply at/above the threshold, else suggest), AUTO_ALL (always auto-apply).
-- `ai_confidence_threshold` is the 0–100 sensitivity gate for AUTO_HIGH_CONFIDENCE.
ALTER TABLE budget_settings
  ADD COLUMN ai_categorization_enabled BOOLEAN     NOT NULL DEFAULT FALSE,
  ADD COLUMN ai_mode                   VARCHAR(24) NOT NULL DEFAULT 'AUTO_HIGH_CONFIDENCE',
  ADD COLUMN ai_confidence_threshold   INTEGER     NOT NULL DEFAULT 75;

-- ── Transaction: persisted AI suggestion ───────────────────────────────────
-- When a transaction is not auto-applied (SUGGEST mode, or below threshold), the model's
-- proposal is stored on the row so the inbox can render "Suggested: X (87%)" without
-- re-running inference on every load. Mirrors how merchant_label / merchant_brand_id
-- enrichment already lives on the transaction. Cleared once the user picks a category.
ALTER TABLE transaction
  ADD COLUMN ai_suggested_category_id BIGINT REFERENCES category(id) ON DELETE SET NULL,
  ADD COLUMN ai_confidence            INTEGER;
