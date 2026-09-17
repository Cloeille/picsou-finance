-- Minimal H2 schema for TransactionRepositoryTest (@DataJpaTest).
--
-- The real schema comes from Flyway (backend/src/main/resources/db/migration), which is
-- PostgreSQL-flavoured (native enum types via CREATE TYPE ... AS ENUM, etc.) and cannot run on
-- H2 -- see docs/conventions/testing.md. This script stands up just enough structure for
-- TransactionRepository's derived-query ordering to be exercised: an `account` row to satisfy
-- the FK on `transaction.account_id`, and a `transaction` table with every column the
-- Transaction entity maps (Hibernate includes all mapped columns in generated INSERTs
-- regardless of what a given test cares about).
--
-- `account` deliberately carries only the columns these queries actually read: this test never
-- persists or queries the Account entity through JPA (see getReference() usage in the test), only
-- via these seed rows, so it never touches Account's Postgres-only `account_type` native-enum
-- column. `deleted_at` is required despite that: Account carries a class-level
-- @SQLRestriction("deleted_at IS NULL"), which Hibernate folds into the JOIN ON clause for *any*
-- query that traverses the `account` association -- including a plain `t.account.id` path in a
-- derived query -- so the column must exist even though this test never sets it (NULL, matching
-- the restriction). `is_manual` is read by the ISIN-repair queries, which must leave the rows of
-- a synced account alone.

-- Dropped first: @Sql runs this script before *each* test method while the in-memory database
-- lives for the whole class, and DDL is not rolled back with the test transaction. Recreating
-- from scratch also guarantees each method starts from the seed rows alone.
DROP TABLE IF EXISTS transaction;
DROP TABLE IF EXISTS account;

CREATE TABLE account (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    is_manual  BOOLEAN NOT NULL DEFAULT TRUE,
    deleted_at TIMESTAMP
);

INSERT INTO account (id, is_manual) VALUES (1, TRUE);
INSERT INTO account (id, is_manual) VALUES (2, FALSE);

CREATE TABLE transaction (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    account_id      BIGINT          NOT NULL,
    date            DATE            NOT NULL,
    description     VARCHAR(255)    NOT NULL,
    amount          DECIMAL(20, 8)  NOT NULL,
    type            VARCHAR(100),
    category        VARCHAR(100),
    native_currency VARCHAR(10)     NOT NULL,
    created_at      TIMESTAMP       NOT NULL,
    is_manual       BOOLEAN         NOT NULL,
    external_transaction_id VARCHAR(128),
    tx_type         VARCHAR(20),
    ticker          VARCHAR(30),
    name            VARCHAR(100),
    quantity        DECIMAL(20, 8),
    price_per_unit  DECIMAL(20, 8),
    fees            DECIMAL(20, 8)
);
