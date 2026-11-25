-- Adds a server-side counter bumped on credential change so outstanding
-- access/refresh JWTs (stateless) can be invalidated by claim mismatch.
ALTER TABLE app_user
    ADD COLUMN token_version BIGINT NOT NULL DEFAULT 0;
