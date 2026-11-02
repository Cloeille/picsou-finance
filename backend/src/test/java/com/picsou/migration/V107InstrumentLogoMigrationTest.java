package com.picsou.migration;

import com.picsou.support.PostgresContainers;
import com.picsou.support.RequiresPostgres;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V107 applies on top of the whole chain, and its CHECK constraints are what make the table's
 * status trustworthy: the read path hands the UI a URL for every STORED row, so a STORED row
 * without bytes would be a mark that 404s on every render.
 */
@RequiresPostgres
class V107InstrumentLogoMigrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = PostgresContainers.newContainer();

    @BeforeAll
    static void migrate() {
        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations("classpath:db/migration")
            .target("107")
            .load()
            .migrate();
    }

    private static void exec(String sql) throws SQLException {
        try (Connection c = PostgresContainers.connection(POSTGRES); Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }

    @Test
    void acceptsEveryLegalRowShape() throws SQLException {
        exec("INSERT INTO instrument_logo (ticker, status, image, content_type, image_dark, content_type_dark, "
            + "attempted_at, fetched_at) VALUES ('AAPL', 'STORED', '\\x89504e47', 'image/png', '\\x89504e47', "
            + "'image/png', NOW(), NOW())");
        exec("INSERT INTO instrument_logo (ticker, status, image, content_type, attempted_at, fetched_at) "
            + "VALUES ('MC.PA', 'STORED', '\\x89504e47', 'image/png', NOW(), NOW())");
        exec("INSERT INTO instrument_logo (ticker, status, attempted_at) VALUES ('NOLOGO', 'ABSENT', NOW())");
        exec("INSERT INTO instrument_logo (ticker, status, attempted_at) VALUES ('DOWN', 'FAILED', NOW())");

        try (Connection c = PostgresContainers.connection(POSTGRES); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT octet_length(image) FROM instrument_logo WHERE ticker = 'AAPL'")) {
            rs.next();
            assertThat(rs.getInt(1)).isEqualTo(4);
        }
    }

    @Test
    void refusesAStoredRowWithoutAnImage() {
        assertThatThrownBy(() -> exec("INSERT INTO instrument_logo (ticker, status, attempted_at) "
            + "VALUES ('BAD1', 'STORED', NOW())"))
            .hasMessageContaining("ck_instrument_logo_stored_image");
    }

    @Test
    void refusesAMissCarryingAnImage() {
        assertThatThrownBy(() -> exec("INSERT INTO instrument_logo (ticker, status, image, content_type, "
            + "attempted_at, fetched_at) VALUES ('BAD2', 'ABSENT', '\\x00', 'image/png', NOW(), NOW())"))
            .hasMessageContaining("ck_instrument_logo_stored_image");
    }

    @Test
    void refusesAnUnknownStatus_aDarkImageWithoutItsType_andADuplicateTicker() throws SQLException {
        assertThatThrownBy(() -> exec("INSERT INTO instrument_logo (ticker, status, attempted_at) "
            + "VALUES ('BAD3', 'PENDING', NOW())"))
            .hasMessageContaining("ck_instrument_logo_status");
        assertThatThrownBy(() -> exec("INSERT INTO instrument_logo (ticker, status, image, content_type, image_dark, "
            + "attempted_at, fetched_at) VALUES ('BAD4', 'STORED', '\\x00', 'image/png', '\\x00', NOW(), NOW())"))
            .hasMessageContaining("ck_instrument_logo_dark_pair");

        exec("INSERT INTO instrument_logo (ticker, status, attempted_at) VALUES ('ONCE', 'ABSENT', NOW())");
        assertThatThrownBy(() -> exec("INSERT INTO instrument_logo (ticker, status, attempted_at) "
            + "VALUES ('ONCE', 'FAILED', NOW())"))
            .hasMessageContaining("uk_instrument_logo_ticker");
    }
}
