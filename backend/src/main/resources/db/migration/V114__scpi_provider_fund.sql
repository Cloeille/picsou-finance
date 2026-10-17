-- V114: One row per fund a CORUM or Sofidy sync has seen for a member (GH issue #232).
--
-- A sync now creates or adopts the SCPI account of a fund it has never seen.
-- This table is what keeps that safe:
--   * ignored    -- the tombstone. A member who deleted or unlinked the account
--                   of a fund does not get it back on the next sync.
--   * last_*     -- the per-fund outcome of the latest sync, so the UI can say
--                   "2 accounts created, 1 updated" instead of a bare success.
-- The account link itself stays on scpi_position (corum_fund_code /
-- sofidy_fund_code), whose per-member unique indexes are what stop a re-sync
-- or a second connection from creating a duplicate account.
CREATE TABLE scpi_provider_fund (
    id                BIGSERIAL PRIMARY KEY,
    member_id         BIGINT NOT NULL REFERENCES family_member(id) ON DELETE CASCADE,
    provider          VARCHAR(16) NOT NULL,
    fund_code         VARCHAR(40) NOT NULL,
    label             VARCHAR(200),
    ignored           BOOLEAN NOT NULL DEFAULT FALSE,
    last_outcome      VARCHAR(24),
    last_seen_at      TIMESTAMPTZ,
    last_synced_at    TIMESTAMPTZ,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_scpi_provider_fund UNIQUE (member_id, provider, fund_code),
    CONSTRAINT ck_scpi_provider_fund_provider
        CHECK (provider IN ('CORUM', 'SOFIDY')),
    CONSTRAINT ck_scpi_provider_fund_outcome
        CHECK (last_outcome IS NULL OR last_outcome IN (
            'CREATED', 'LINKED', 'UPDATED', 'PRICE_INCOMPLETE',
            'AMBIGUOUS', 'IGNORED', 'MISSING_AT_PROVIDER'
        ))
);
