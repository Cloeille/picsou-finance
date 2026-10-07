-- V48: Revolut sidecar session storage (one row per family member)

CREATE TABLE revolut_session (
    id             BIGSERIAL PRIMARY KEY,
    member_id      BIGINT NOT NULL REFERENCES family_member(id),
    storage_state  TEXT NOT NULL,
    expires_at     TIMESTAMPTZ,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
