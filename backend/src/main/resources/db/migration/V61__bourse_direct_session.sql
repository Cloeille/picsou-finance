CREATE TABLE bourse_direct_session (
    id             BIGSERIAL PRIMARY KEY,
    member_id      BIGINT NOT NULL UNIQUE REFERENCES family_member(id),
    session_state  TEXT NOT NULL,
    last_validated_at TIMESTAMPTZ,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
