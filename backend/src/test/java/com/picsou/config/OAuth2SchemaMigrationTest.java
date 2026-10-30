package com.picsou.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards that Flyway migration {@code V54__oauth2_authorization_server.sql} provisions exactly the
 * three JDBC tables that Spring Authorization Server's {@code Jdbc*} repositories
 * ({@code JdbcRegisteredClientRepository}, {@code JdbcOAuth2AuthorizationService},
 * {@code JdbcOAuth2AuthorizationConsentService}) expect at startup — before any later task wires
 * those repositories against this schema, per the Remote-MCP OAuth plan
 * ({@code docs/briefs/2026-07-12-remote-mcp-oauth-plan.md}, Task 1).
 *
 * <p>Boots the full application context (so Flyway runs every migration in order, V1..V54) against
 * a real Postgres 16 via Testcontainers, mirroring {@code BudgetSeedWriteOnReadPostgresTest} — the
 * project's established pattern for the rare case where DB fidelity (here: Flyway actually applying
 * cleanly against Postgres, not just parsing) is the point. {@code disabledWithoutDocker = true}
 * self-skips on machines/CI without a Docker daemon rather than fail.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class OAuth2SchemaMigrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    /**
     * The full application context refuses to boot without two secrets that have no default in
     * {@code application.yml}: {@code JwtUtil} demands a signing key of at least 32 characters, and
     * {@code CryptoEncryption} demands a non-blank Base64 AES key. Supply deterministic test values
     * (the all-zero 32-byte key is a valid AES-256 key for round-tripping in tests).
     */
    @DynamicPropertySource
    static void secrets(DynamicPropertyRegistry registry) {
        registry.add("app.jwt.secret", () -> "test-jwt-secret-test-jwt-secret-0123456789");
        registry.add("app.crypto.encryption-key", () -> Base64.getEncoder().encodeToString(new byte[32]));
    }

    @Autowired JdbcTemplate jdbcTemplate;

    /**
     * The core regression guard: all three Spring AS JDBC tables exist after Flyway has run every
     * migration, named exactly as the framework's own {@code Jdbc*} repositories hard-code them.
     */
    @Test
    void migrationCreatesAllThreeOAuth2Tables() {
        List<String> tables = jdbcTemplate.queryForList(
            """
            SELECT table_name FROM information_schema.tables
            WHERE table_schema = 'public'
              AND table_name IN ('oauth2_registered_client', 'oauth2_authorization', 'oauth2_authorization_consent')
            ORDER BY table_name
            """,
            String.class);

        assertThat(tables).containsExactly(
            "oauth2_authorization",
            "oauth2_authorization_consent",
            "oauth2_registered_client");
    }

    /**
     * A schema that merely has the right table names but wrong/missing columns would still pass the
     * table-existence check above yet break {@code JdbcRegisteredClientRepository} at runtime — so
     * spot-check one load-bearing column per table (the ones the row mappers read first / the
     * consent table's composite primary key).
     */
    @Test
    void oauth2TablesHaveExpectedKeyColumns() {
        assertThat(columnNames("oauth2_registered_client"))
            .contains("id", "client_id", "client_authentication_methods", "authorization_grant_types",
                "redirect_uris", "scopes", "client_settings", "token_settings");
        assertThat(columnNames("oauth2_authorization"))
            .contains("id", "registered_client_id", "principal_name", "authorization_grant_type",
                "attributes", "state", "access_token_value", "refresh_token_value");
        assertThat(columnNames("oauth2_authorization_consent"))
            .contains("registered_client_id", "principal_name", "authorities");
    }

    private List<String> columnNames(String table) {
        return jdbcTemplate.queryForList(
            "SELECT column_name FROM information_schema.columns WHERE table_schema = 'public' AND table_name = ?",
            String.class, table);
    }
}
