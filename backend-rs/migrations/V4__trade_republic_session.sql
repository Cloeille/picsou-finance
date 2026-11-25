-- V4: Trade Republic session token storage (single-row table)

CREATE TABLE trade_republic_session (
    id            BIGSERIAL PRIMARY KEY,
    session_token VARCHAR(1000) NOT NULL,
    expires_at    TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
