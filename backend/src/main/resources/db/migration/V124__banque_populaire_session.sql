-- Banque Populaire sidecar session: one row per member, the encrypted session state
-- (cookies + authorize params, encrypted by the backend), and the persisted job state
-- machine an interrupted sync is recovered from.
--
-- Same shape as caisse_epargne_session (V109 + V110): Banque Populaire runs on the same
-- BPCE platform and goes through the same sync engine. Its own table keeps the two banks'
-- sessions apart. There is NO encrypted_credentials column: only session cookies are
-- retained, never the password nor the Secur'Pass, so an expired session means the user
-- reconnects. The regional bank chosen at login IS stored (region): it is not a secret,
-- it names the bank the sync stamps on the accounts it imports and looks the logo up under.
-- A new login replaces the row, region included.
CREATE TABLE banque_populaire_session (
    id                     BIGSERIAL PRIMARY KEY,
    member_id              BIGINT NOT NULL UNIQUE,
    region                 VARCHAR(32) NOT NULL,
    session_state          TEXT NOT NULL,
    last_validated_at      TIMESTAMPTZ,
    is_active              BOOLEAN NOT NULL DEFAULT TRUE,
    sync_status            VARCHAR(16) NOT NULL DEFAULT 'IDLE',
    last_sync_started_at   TIMESTAMPTZ,
    last_sync_completed_at TIMESTAMPTZ,
    last_sync_error        VARCHAR(40),
    -- JSON array of {externalId, familyCode} for contracts the sidecar reported but
    -- Picsou does not import. Counted in the status response, never turned into accounts.
    unsupported_contracts  TEXT,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_banque_populaire_session_member
        FOREIGN KEY (member_id) REFERENCES family_member(id) ON DELETE CASCADE,
    -- Kept in lockstep with BanquePopulaireRegion.
    CONSTRAINT ck_banque_populaire_session_region
        CHECK (region IN (
            'ALSACE_LORRAINE_CHAMPAGNE',
            'AQUITAINE_CENTRE_ATLANTIQUE',
            'AUVERGNE_RHONE_ALPES',
            'BOURGOGNE_FRANCHE_COMTE',
            'BRED',
            'GRAND_OUEST',
            'MEDITERRANEE',
            'NORD',
            'OCCITANE',
            'RIVES_DE_PARIS',
            'SUD',
            'VAL_DE_FRANCE'
        )),
    CONSTRAINT ck_banque_populaire_session_sync_status
        CHECK (sync_status IN ('IDLE', 'QUEUED', 'RUNNING', 'SUCCESS', 'FAILED')),
    -- Kept in lockstep with SidecarBank.BANQUE_POPULAIRE (SidecarBankCodesTest pins it): a code missing here blows up the
    -- write that records a failed sync, turning a diagnosable error into a 500.
    CONSTRAINT ck_banque_populaire_session_last_sync_error
        CHECK (
            last_sync_error IS NULL
            OR last_sync_error IN (
                'SESSION_EXPIRED',
                'UPSTREAM_UNAVAILABLE',
                'UPSTREAM_FORMAT_CHANGED',
                'INVALID_SESSION_STATE',
                'INTERNAL_ERROR',
                'INVALID_CREDENTIALS',
                'KEYPAD_CHANGED',
                'APP_VALIDATION_TIMEOUT',
                'AUTH_ATTEMPT_EXPIRED',
                'KEYPAD_EXPIRED',
                'INVALID_POSITIONS'
            )
        ),
    CONSTRAINT ck_banque_populaire_session_failed_error
        CHECK (
            (sync_status = 'FAILED' AND last_sync_error IS NOT NULL)
            OR (sync_status <> 'FAILED' AND last_sync_error IS NULL)
        )
);
