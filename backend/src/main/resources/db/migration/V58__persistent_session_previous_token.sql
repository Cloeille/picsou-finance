-- V58: grace window for concurrent multi-tab "Remember Me" restores.
--
-- When several tabs are restored at once they each present the same persistent
-- token; the first request rotates it and the others would then trip theft
-- detection and revoke the whole series, logging the user out everywhere.
--
-- Remember the immediately-previous token hash (and when it was superseded) so
-- a concurrent tab presenting it within a short grace window is accepted rather
-- than treated as a replayed/stolen token. See PersistentSessionService and
-- docs/features/mfa-and-remember-me.md.
ALTER TABLE persistent_session
    ADD COLUMN previous_token_hash TEXT,
    ADD COLUMN previous_token_at   TIMESTAMPTZ;
