-- Minimal H2 schema for WiseConnectionPersistenceTest (@DataJpaTest).
--
-- Same rationale as simplefin-status-writer-test-schema.sql: the real migrations are
-- PostgreSQL-flavoured and cannot run on H2, so this stands up the two tables the test touches.
-- Mirrors V131__wise_connection.sql (api_token TEXT, status VARCHAR(20), one row per member).

CREATE TABLE IF NOT EXISTS family_member (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    display_name VARCHAR(100) NOT NULL,
    avatar_color VARCHAR(7)   NOT NULL,
    is_managed   BOOLEAN      NOT NULL,
    created_at   TIMESTAMP    NOT NULL,
    updated_at   TIMESTAMP    NOT NULL
);

CREATE TABLE IF NOT EXISTS wise_connection (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    member_id      BIGINT       NOT NULL REFERENCES family_member(id),
    api_token      CLOB         NOT NULL,
    status         VARCHAR(20)  NOT NULL,
    last_synced_at TIMESTAMP,
    created_at     TIMESTAMP    NOT NULL,
    updated_at     TIMESTAMP    NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_wise_connection_member ON wise_connection(member_id);
