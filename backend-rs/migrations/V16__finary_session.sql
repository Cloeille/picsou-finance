-- V16: Finary session for encrypted credential storage

CREATE TABLE finary_session (
    id              BIGSERIAL PRIMARY KEY,
    email           VARCHAR(500)  NOT NULL,
    password        VARCHAR(500)  NOT NULL,
    status          VARCHAR(20)   NOT NULL DEFAULT 'CONNECTED',
    last_synced_at  TIMESTAMPTZ,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);
