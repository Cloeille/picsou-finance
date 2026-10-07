-- Minimal H2 schema for AccountHoldingRepositoryTest (@DataJpaTest).
--
-- Same reasoning as sql/transaction-repository-test-schema.sql: the real migrations are
-- PostgreSQL-flavoured (native enum types) and cannot run on H2 -- see
-- docs/conventions/testing.md -- so this stands up just the structure
-- existsForReadableAccount touches.
--
-- `account.deleted_at` is required even though no test sets it: Account carries a class-level
-- @SQLRestriction("deleted_at IS NULL") that Hibernate folds into any query traversing the
-- association. `account.member_id` is the owning member the query's first predicate reads, and
-- `account_ownership` is the co-ownership the second one reads -- the whole point of the test.

DROP TABLE IF EXISTS account_holding;
DROP TABLE IF EXISTS account_ownership;
DROP TABLE IF EXISTS account;
DROP TABLE IF EXISTS family_member;

CREATE TABLE family_member (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    display_name VARCHAR(100) NOT NULL,
    avatar_color VARCHAR(7)   NOT NULL,
    is_managed   BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at   TIMESTAMP    NOT NULL,
    updated_at   TIMESTAMP    NOT NULL
);

CREATE TABLE account (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    member_id  BIGINT NOT NULL,
    is_manual  BOOLEAN NOT NULL DEFAULT TRUE,
    deleted_at TIMESTAMP
);

CREATE TABLE account_ownership (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    account_id    BIGINT        NOT NULL,
    member_id     BIGINT        NOT NULL,
    share_percent DECIMAL(6, 3) NOT NULL,
    created_at    TIMESTAMP     NOT NULL,
    updated_at    TIMESTAMP     NOT NULL
);

CREATE TABLE account_holding (
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    account_id         BIGINT         NOT NULL,
    ticker             VARCHAR(30)    NOT NULL,
    name               VARCHAR(100),
    quantity           DECIMAL(20, 8) NOT NULL,
    average_buy_in     DECIMAL(20, 8),
    current_price      DECIMAL(20, 8),
    quote_currency     VARCHAR(3),
    provider_value_eur DECIMAL(20, 8),
    provider_pnl_eur   DECIMAL(20, 8),
    last_synced_at     TIMESTAMP,
    created_at         TIMESTAMP      NOT NULL,
    updated_at         TIMESTAMP      NOT NULL
);

-- member 1 owns account 1 (with a line) and account 3 (no line);
-- member 2 owns account 2, which member 1 co-owns through account_ownership.
INSERT INTO family_member (id, display_name, avatar_color, created_at, updated_at)
VALUES (1, 'Owner', '#6366f1', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
       (2, 'Other', '#22c55e', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

INSERT INTO account (id, member_id) VALUES (1, 1), (2, 2), (3, 1);

INSERT INTO account_ownership (account_id, member_id, share_percent, created_at, updated_at)
VALUES (2, 1, 50.000, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

INSERT INTO account_holding (account_id, ticker, quantity, created_at, updated_at)
VALUES (1, 'AI.PA', 10, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
       (2, 'CW8.PA', 5, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
