-- One-shot gate used by PriceFxCleanupRunner: when 'false', the runner wipes
-- price_snapshot at startup so PriceBackfillRunner can rebuild the 12-month
-- history with FX-corrected Yahoo prices. The runner flips it to 'true' once
-- the purge has succeeded, making the operation idempotent across reboots.
INSERT INTO app_setting (setting_key, value)
VALUES ('price.fx_fix_cleanup_done', 'false')
ON CONFLICT (setting_key) DO NOTHING;
