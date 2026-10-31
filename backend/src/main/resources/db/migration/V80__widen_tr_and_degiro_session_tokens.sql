-- V80: Stop bounding encrypted session tokens by a guessed VARCHAR length.
-- Trade Republic now issues tr_session tokens whose AES-GCM-encrypted, base64-encoded
-- form exceeds VARCHAR(2000) (GH issue #115: completeAuth 500s with
-- "value too long for type character varying(2000)", session never persists,
-- next sync loops on "please reconnect").
-- DEGIRO's session_blob (V71) stores the same class of encrypted blob under
-- VARCHAR(4000) and shares the latent ceiling, so it goes to TEXT as well —
-- consistent with bourso_session / bourse_direct_session / amundi_session,
-- which already use TEXT for their encrypted session state.

ALTER TABLE trade_republic_session ALTER COLUMN session_token TYPE TEXT;
ALTER TABLE trade_republic_session ALTER COLUMN refresh_token TYPE TEXT;
ALTER TABLE degiro_session ALTER COLUMN session_blob TYPE TEXT;
