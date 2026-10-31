-- V51: OAuth state nonce on requisition
--
-- Random UUID sent as the OAuth `state` parameter at initiation and echoed
-- back by Enable Banking on the redirect. Lets /sync/complete resolve the
-- exact requisition the callback belongs to instead of guessing "most recent
-- CREATED for the current member" (wrong-bank binding, no CSRF protection,
-- broken admin impersonation). Single-use: cleared once the session is linked.

ALTER TABLE requisition ADD COLUMN oauth_state VARCHAR(64);

-- Multiple NULLs are allowed in a unique index; only live nonces must be unique.
CREATE UNIQUE INDEX idx_requisition_oauth_state ON requisition (oauth_state);
