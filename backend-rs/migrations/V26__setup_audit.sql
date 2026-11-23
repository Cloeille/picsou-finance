-- Append-only audit trail for the first-launch setup wizard.
-- Never truncated: forensics rely on the full history even after COMPLETE.
CREATE TABLE setup_audit (
    id              BIGSERIAL     PRIMARY KEY,
    event           VARCHAR(80)   NOT NULL,
    actor_username  VARCHAR(50),
    ip              VARCHAR(45),
    user_agent      VARCHAR(500),
    details         TEXT,
    at              TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_setup_audit_event_at ON setup_audit(event, at);
