-- 1. family_member: core identity table
CREATE TABLE family_member (
    id           BIGSERIAL PRIMARY KEY,
    display_name VARCHAR(100)  NOT NULL,
    avatar_color VARCHAR(7)    NOT NULL DEFAULT '#6366f1',
    is_managed   BOOLEAN       NOT NULL DEFAULT FALSE,
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

-- 2. app_user gains family system columns (nullable for migration, NOT NULL set in V22)
ALTER TABLE app_user ADD COLUMN member_id                 BIGINT REFERENCES family_member(id) ON DELETE CASCADE;
ALTER TABLE app_user ADD COLUMN role                      VARCHAR(20) NOT NULL DEFAULT 'MEMBER';
ALTER TABLE app_user ADD COLUMN is_activated              BOOLEAN    NOT NULL DEFAULT TRUE;
ALTER TABLE app_user ADD COLUMN activation_token          VARCHAR(64) UNIQUE;
ALTER TABLE app_user ADD COLUMN activation_token_expires  TIMESTAMPTZ;
ALTER TABLE app_user ADD COLUMN acknowledged_warning      BOOLEAN    NOT NULL DEFAULT FALSE;

-- 3. Add nullable member_id to all 9 owner tables
ALTER TABLE account                  ADD COLUMN member_id BIGINT REFERENCES family_member(id) ON DELETE CASCADE;
ALTER TABLE goal                     ADD COLUMN member_id BIGINT REFERENCES family_member(id) ON DELETE CASCADE;
ALTER TABLE requisition              ADD COLUMN member_id BIGINT REFERENCES family_member(id) ON DELETE CASCADE;
ALTER TABLE trade_republic_session   ADD COLUMN member_id BIGINT REFERENCES family_member(id) ON DELETE CASCADE;
ALTER TABLE crypto_exchange_session  ADD COLUMN member_id BIGINT REFERENCES family_member(id) ON DELETE CASCADE;
ALTER TABLE finary_session           ADD COLUMN member_id BIGINT REFERENCES family_member(id) ON DELETE CASCADE;
ALTER TABLE wallet_address           ADD COLUMN member_id BIGINT REFERENCES family_member(id) ON DELETE CASCADE;
ALTER TABLE real_estate_metadata     ADD COLUMN member_id BIGINT REFERENCES family_member(id) ON DELETE CASCADE;
ALTER TABLE debt                     ADD COLUMN member_id BIGINT REFERENCES family_member(id) ON DELETE CASCADE;

-- 4. Indexes for fast member-scoped queries
CREATE INDEX idx_account_member                  ON account(member_id);
CREATE INDEX idx_goal_member                     ON goal(member_id);
CREATE INDEX idx_requisition_member              ON requisition(member_id);
CREATE INDEX idx_trade_republic_session_member   ON trade_republic_session(member_id);
CREATE INDEX idx_crypto_exchange_session_member  ON crypto_exchange_session(member_id);
CREATE INDEX idx_finary_session_member           ON finary_session(member_id);
CREATE INDEX idx_wallet_address_member           ON wallet_address(member_id);
CREATE INDEX idx_real_estate_metadata_member     ON real_estate_metadata(member_id);
CREATE INDEX idx_debt_member                     ON debt(member_id);

-- 5. Sharing system
CREATE TYPE sharing_level AS ENUM ('ALL', 'NONE', 'MANUAL');

CREATE TABLE sharing_settings (
    id             BIGSERIAL PRIMARY KEY,
    member_id      BIGINT         NOT NULL REFERENCES family_member(id) ON DELETE CASCADE,
    resource_type  VARCHAR(20)    NOT NULL,
    sharing_level  sharing_level  NOT NULL DEFAULT 'NONE',
    updated_at     TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    UNIQUE (member_id, resource_type)
);

CREATE TABLE shared_resource (
    id               BIGSERIAL PRIMARY KEY,
    owner_member_id  BIGINT      NOT NULL REFERENCES family_member(id) ON DELETE CASCADE,
    resource_type    VARCHAR(20) NOT NULL,
    resource_id      BIGINT      NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (owner_member_id, resource_type, resource_id)
);

CREATE INDEX idx_shared_resource_owner ON shared_resource(owner_member_id, resource_type);

-- 6. Goal contributors (multi-member goals)
CREATE TABLE goal_contributor (
    goal_id   BIGINT NOT NULL REFERENCES goal(id) ON DELETE CASCADE,
    member_id BIGINT NOT NULL REFERENCES family_member(id) ON DELETE CASCADE,
    PRIMARY KEY (goal_id, member_id)
);

-- 7. goal_manual_contribution gets member_id for per-member tracking
ALTER TABLE goal_manual_contribution ADD COLUMN member_id BIGINT REFERENCES family_member(id) ON DELETE CASCADE;
