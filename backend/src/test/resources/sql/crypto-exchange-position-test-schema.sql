-- Minimal H2 schema for CryptoExchangePositionRepositoryTest (@DataJpaTest).
--
-- The real schema comes from Flyway, which is PostgreSQL-flavoured and cannot run on H2 (see
-- docs/conventions/testing.md). This stands up just enough for the delete-then-reinsert rewrite:
-- an `account` row to satisfy the FK, and `crypto_exchange_position` with the same UNIQUE
-- constraint V74 creates — without it this test proves nothing, since the whole point is that the
-- constraint is what the old code tripped over.
--
-- `account` carries only `id` and `deleted_at`, for the same reason as the transaction-repository
-- schema: Account's @SQLRestriction("deleted_at IS NULL") is folded into any query traversing the
-- association, while its Postgres-only native-enum columns are never touched here.

-- Dropped first: @Sql runs once per test method against the same in-memory database, so the
-- script has to be re-runnable — and starting from an empty table also isolates the methods.
DROP TABLE IF EXISTS crypto_exchange_position;
DROP TABLE IF EXISTS account;

CREATE TABLE account (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    deleted_at TIMESTAMP
);

CREATE TABLE crypto_exchange_position (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    account_id     BIGINT        NOT NULL,
    product        VARCHAR(20)   NOT NULL,
    ticker         VARCHAR(30)   NOT NULL,
    quantity       NUMERIC(20,8) NOT NULL,
    principal      NUMERIC(20,8),
    interest       NUMERIC(20,8),
    last_synced_at TIMESTAMP,
    created_at     TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_crypto_exchange_position_account_product_ticker UNIQUE (account_id, product, ticker),
    CONSTRAINT fk_crypto_exchange_position_account FOREIGN KEY (account_id) REFERENCES account(id)
);

-- Two accounts, because one cannot show that `deleteAllForAccount` filters by account at all:
-- with a single row in the table, dropping the WHERE clause from the JPQL delete would pass every
-- assertion while wiping every other account's breakdown on each sync.
INSERT INTO account (id, deleted_at) VALUES (1, NULL);
INSERT INTO account (id, deleted_at) VALUES (2, NULL);
