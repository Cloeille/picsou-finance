CREATE TABLE ai_call_log (
  id               BIGSERIAL PRIMARY KEY,
  created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
  member_id        BIGINT       REFERENCES family_member(id) ON DELETE CASCADE,
  transaction_id   BIGINT       REFERENCES transaction(id)   ON DELETE SET NULL,
  merchant_label   VARCHAR(512),
  batch_id         UUID,
  provider         VARCHAR(32)  NOT NULL,
  model            VARCHAR(128),
  prompt           TEXT,
  response         TEXT,
  prompt_tokens    INTEGER,
  completion_tokens INTEGER,
  total_tokens     INTEGER,
  latency_ms       INTEGER,
  status           VARCHAR(16)  NOT NULL,
  error            TEXT,
  chosen_slug      VARCHAR(64),
  confidence       INTEGER,
  applied          BOOLEAN      NOT NULL DEFAULT FALSE
);
CREATE INDEX idx_ai_call_log_created ON ai_call_log(created_at DESC);
CREATE INDEX idx_ai_call_log_member  ON ai_call_log(member_id, created_at DESC);
