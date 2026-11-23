-- V3: GoCardless requisitions tracking

CREATE TYPE requisition_status AS ENUM ('CREATED', 'LINKED', 'EXPIRED', 'FAILED');

CREATE TABLE requisition (
    id               BIGSERIAL PRIMARY KEY,
    requisition_id   VARCHAR(100) NOT NULL UNIQUE,  -- GoCardless UUID
    institution_id   VARCHAR(100) NOT NULL,
    institution_name VARCHAR(200),
    status           requisition_status NOT NULL DEFAULT 'CREATED',
    auth_link        TEXT,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_requisition_status ON requisition(status);
