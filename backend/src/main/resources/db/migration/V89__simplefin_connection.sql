-- V89: SimpleFIN access URL, one row per family member.
--
-- The setup token is claimed once and discarded. What we keep is the access URL,
-- which embeds HTTP Basic credentials, so the column stores AES-256-GCM ciphertext
-- and is TEXT (the URL plus the encryption overhead does not fit a short varchar).

CREATE TABLE simplefin_connection (
    id              BIGSERIAL PRIMARY KEY,
    member_id       BIGINT        NOT NULL REFERENCES family_member(id) ON DELETE CASCADE,
    access_url      TEXT          NOT NULL,
    status          VARCHAR(20)   NOT NULL DEFAULT 'CONNECTED',
    last_synced_at  TIMESTAMPTZ,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX idx_simplefin_connection_member ON simplefin_connection(member_id);

-- SimpleFIN connection and account ids are opaque and can exceed the old limits
-- once prefixed (sfin_{conn}_{account}).
ALTER TABLE account ALTER COLUMN external_account_id TYPE VARCHAR(255);
ALTER TABLE transaction ALTER COLUMN external_transaction_id TYPE VARCHAR(255);
