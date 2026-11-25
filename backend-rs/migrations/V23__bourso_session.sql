-- V23: BoursoBank session cookie storage (one row per family member)

CREATE TABLE bourso_session (
    id               BIGSERIAL PRIMARY KEY,
    member_id        BIGINT NOT NULL REFERENCES family_member(id),
    session_cookies  VARCHAR(4000) NOT NULL,
    expires_at       TIMESTAMPTZ,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
