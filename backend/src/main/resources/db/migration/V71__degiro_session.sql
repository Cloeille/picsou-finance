-- V71: DEGIRO session storage (one row per family member)
--
-- DEGIRO's unofficial session cookie times out after ~30 minutes of inactivity
-- and there is no refresh token, unlike Trade Republic / Bourse Direct / Bourso
-- sessions which last days to weeks. Picsou therefore never stores the account's
-- TOTP secret to re-authenticate unattended (see
-- docs/decisions/2026-08-05-degiro-session-only-no-stored-totp.md): status simply
-- flips to REAUTH_REQUIRED when a sync call finds the session expired, and there
-- is no scheduled background resync for this integration.

CREATE TABLE degiro_session (
    id              BIGSERIAL PRIMARY KEY,
    -- ON DELETE CASCADE, matching what V61 retrofitted onto bourse_direct_session:
    -- without it, deleting a family member fails outright once they have a stored
    -- DEGIRO session, since nothing in the member-deletion path clears this row first.
    member_id       BIGINT NOT NULL,
    session_blob    VARCHAR(4000) NOT NULL,
    status          VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    last_synced_at  TIMESTAMPTZ,
    last_error      VARCHAR(40),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_degiro_session_member UNIQUE (member_id),
    CONSTRAINT fk_degiro_session_member
        FOREIGN KEY (member_id) REFERENCES family_member(id) ON DELETE CASCADE,
    CONSTRAINT ck_degiro_session_status
        CHECK (status IN ('ACTIVE', 'REAUTH_REQUIRED', 'FAILED')),
    CONSTRAINT ck_degiro_session_failed_error
        CHECK (
            (status = 'FAILED' AND last_error IS NOT NULL)
            OR (status <> 'FAILED')
        )
);
