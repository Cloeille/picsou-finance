-- Minimal H2 schema for IbkrStatusWriterTest (@DataJpaTest).
--
-- Same rationale as transaction-repository-test-schema.sql: the real migrations are
-- PostgreSQL-flavoured and cannot run on H2 (docs/conventions/testing.md), so this stands up
-- just the two tables the test touches. Both entities extend AuditableEntity, whose
-- created_at/updated_at are filled by Spring Data JPA auditing (@EnableJpaAuditing on
-- PicsouApplication, which @DataJpaTest loads as the configuration root).

-- IF NOT EXISTS: @Sql runs before every test method against the same in-memory H2.
CREATE TABLE IF NOT EXISTS family_member (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    display_name VARCHAR(100) NOT NULL,
    avatar_color VARCHAR(7)   NOT NULL,
    is_managed   BOOLEAN      NOT NULL,
    created_at   TIMESTAMP    NOT NULL,
    updated_at   TIMESTAMP    NOT NULL
);

CREATE TABLE IF NOT EXISTS ibkr_connection (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    member_id      BIGINT       NOT NULL REFERENCES family_member(id),
    token          VARCHAR(500) NOT NULL,
    query_id       VARCHAR(500) NOT NULL,
    status         VARCHAR(20)  NOT NULL,
    last_synced_at TIMESTAMP,
    created_at     TIMESTAMP    NOT NULL,
    updated_at     TIMESTAMP    NOT NULL
);
