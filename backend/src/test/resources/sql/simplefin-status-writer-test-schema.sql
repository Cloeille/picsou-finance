-- Minimal H2 schema for SimplefinStatusWriterTest (@DataJpaTest).
--
-- Same rationale as ibkr-status-writer-test-schema.sql: the real migrations are
-- PostgreSQL-flavoured and cannot run on H2 (docs/conventions/testing.md), so this stands up
-- just the two tables the test touches. Both entities extend AuditableEntity, whose
-- created_at/updated_at are filled by Spring Data JPA auditing.
-- Mirrors V109__simplefin_connection.sql (access_url TEXT, status VARCHAR(20), one row per member).

-- IF NOT EXISTS: @Sql runs before every test method against the same in-memory H2.
CREATE TABLE IF NOT EXISTS family_member (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    display_name VARCHAR(100) NOT NULL,
    avatar_color VARCHAR(7)   NOT NULL,
    is_managed   BOOLEAN      NOT NULL,
    created_at   TIMESTAMP    NOT NULL,
    updated_at   TIMESTAMP    NOT NULL
);

CREATE TABLE IF NOT EXISTS simplefin_connection (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    member_id      BIGINT       NOT NULL REFERENCES family_member(id),
    access_url     CLOB         NOT NULL,
    status         VARCHAR(20)  NOT NULL,
    last_synced_at TIMESTAMP,
    created_at     TIMESTAMP    NOT NULL,
    updated_at     TIMESTAMP    NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_simplefin_connection_member ON simplefin_connection(member_id);
