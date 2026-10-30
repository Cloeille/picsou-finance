ALTER TABLE bourse_direct_session
    DROP CONSTRAINT IF EXISTS bourse_direct_session_member_id_fkey;

ALTER TABLE bourse_direct_session
    ADD CONSTRAINT fk_bourse_direct_session_member
        FOREIGN KEY (member_id) REFERENCES family_member(id) ON DELETE CASCADE,
    ADD COLUMN is_active BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN sync_status VARCHAR(16) NOT NULL DEFAULT 'IDLE',
    ADD COLUMN last_sync_started_at TIMESTAMPTZ,
    ADD COLUMN last_sync_completed_at TIMESTAMPTZ,
    ADD COLUMN last_sync_error VARCHAR(40),
    ADD CONSTRAINT ck_bourse_direct_session_sync_status
        CHECK (sync_status IN ('IDLE', 'QUEUED', 'RUNNING', 'SUCCESS', 'FAILED'));

ALTER TABLE account_holding
    ADD COLUMN quote_currency VARCHAR(3),
    ADD COLUMN provider_value_eur NUMERIC(20, 8),
    ADD COLUMN provider_pnl_eur NUMERIC(20, 8),
    ADD CONSTRAINT ck_account_holding_quote_currency
        CHECK (quote_currency IS NULL OR quote_currency ~ '^[A-Z]{3}$');
