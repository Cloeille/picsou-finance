-- V153: Louve Invest SCPI sync (official MCP server, OAuth 2.1). No sidecar container.
--
-- Shaped like the other container-less API connectors (simplefin_connection V113,
-- wise_connection V131, ibkr_connection V59): one connection row per member, its secret
-- stored AES-256-GCM encrypted (TEXT), a status and the last sync time.
--
-- 1. louve_connection holds the member's encrypted token set (null until the first consent)
--    and, while the member is on Louve's consent page, the pending OAuth attempt: the state
--    nonce (single use, cleared when spent, as requisition.oauth_state since V51), the encrypted
--    PKCE verifier, the redirect URI and client id it was started with, and when. An active
--    connection keeps its tokens while a reconnect is pending. grant_generation counts the
--    authorizations stored on the row (a refresh-token rotation does not count): a callback or a
--    sync started under an older one is discarded instead of overwriting a newer connection.
-- 2. scpi_position.louve_fund_code links an account to one Louve fund (scpiUrlId, e.g.
--    'aurore'), unique per member like the CORUM and Sofidy codes, so a re-sync can never
--    write two funds to one account.
-- 3. scpi_provider_fund accepts the LOUVE provider and the UNSUPPORTED_OWNERSHIP outcome
--    (a dismembered line -- nue-propriete or usufruit -- is never valued as full ownership).
--
-- The client id Louve issues by dynamic client registration (RFC 7591) is instance-wide and
-- lives in app_setting (key 'louve.client-id:<sha-256 of the redirect URI>'), so it needs no table.

CREATE TABLE louve_connection (
    id                  BIGSERIAL PRIMARY KEY,
    member_id           BIGINT        NOT NULL REFERENCES family_member(id) ON DELETE CASCADE,
    token_state         TEXT,
    status              VARCHAR(20)   NOT NULL DEFAULT 'PENDING',
    grant_generation    BIGINT        NOT NULL DEFAULT 0,
    last_synced_at      TIMESTAMPTZ,
    oauth_state         VARCHAR(64),
    oauth_verifier      TEXT,
    oauth_redirect_uri  VARCHAR(512),
    oauth_client_id     VARCHAR(255),
    oauth_started_at    TIMESTAMPTZ,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_louve_connection_status
        CHECK (status IN ('PENDING', 'CONNECTED', 'ERROR', 'REAUTH_REQUIRED')),
    CONSTRAINT ck_louve_connection_attempt
        CHECK ((oauth_state IS NULL) = (oauth_verifier IS NULL))
);

CREATE UNIQUE INDEX idx_louve_connection_member ON louve_connection(member_id);

ALTER TABLE scpi_position
    ADD COLUMN louve_fund_code VARCHAR(40);

CREATE UNIQUE INDEX uq_scpi_position_louve_fund
    ON scpi_position (member_id, louve_fund_code)
    WHERE louve_fund_code IS NOT NULL;

ALTER TABLE scpi_provider_fund DROP CONSTRAINT ck_scpi_provider_fund_provider;
ALTER TABLE scpi_provider_fund ADD CONSTRAINT ck_scpi_provider_fund_provider
    CHECK (provider IN ('CORUM', 'SOFIDY', 'LOUVE'));

ALTER TABLE scpi_provider_fund DROP CONSTRAINT ck_scpi_provider_fund_outcome;
ALTER TABLE scpi_provider_fund ADD CONSTRAINT ck_scpi_provider_fund_outcome
    CHECK (last_outcome IS NULL OR last_outcome IN (
        'CREATED', 'LINKED', 'UPDATED', 'PRICE_INCOMPLETE',
        'AMBIGUOUS', 'IGNORED', 'MISSING_AT_PROVIDER', 'UNSUPPORTED_OWNERSHIP'
    ));
