package com.picsou.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIf;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.DockerClientFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V85 rewrites no rows, so by the letter of the testing convention it needs no Testcontainers
 * test. It gets one anyway, because it changes the <em>validity</em> of rows that already exist:
 * it drops {@code chk_goal_deadline}.
 *
 * <p>That constraint was {@code CHECK (deadline > CURRENT_DATE)}, and {@code CURRENT_DATE} is not
 * immutable — PostgreSQL re-evaluates it on every UPDATE of the row. So any {@code save()} on a
 * goal whose deadline had passed failed at the database, which silently broke editing, history
 * backfill and month overrides for exactly the goals a user is most likely to revisit.
 *
 * <p>The point of this test is the assertion at the end: after V85, an expired goal updates.
 */
@Testcontainers
@EnabledIf("dockerAvailable")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class V85GoalTypeMigrationTest {

    static {
        // docker-java otherwise negotiates down to API 1.32, which Engine >= 28 refuses — and it
        // surfaces as the same "no valid Docker environment" a Docker-less machine gives, so the
        // guard below would quietly skip on a perfectly capable host.
        System.setProperty("api.version", "1.44");
    }

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static boolean dockerAvailable() {
        boolean available = DockerClientFactory.instance().isDockerAvailable();
        if (!available && System.getenv("PICSOU_REQUIRE_DOCKER_TESTS") != null) {
            throw new IllegalStateException("Docker is required but unavailable");
        }
        return available;
    }

    private static void migrateTo(String version) {
        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations("classpath:db/migration")
            .target(version)
            .load()
            .migrate();
    }

    private static Connection connection() throws SQLException {
        return DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    @BeforeAll
    static void migrateAndSeed() throws SQLException {
        migrateTo("84");

        try (Connection c = connection(); Statement st = c.createStatement()) {
            // goal only references family_member; app_user is not needed to exercise the
            // constraint, and seeding it would drag in the whole two-tier identity setup.
            st.execute("INSERT INTO family_member (id, display_name) VALUES (1, 'Member')");

            // A goal that is still in the future — the only kind V2's constraint allowed us to
            // insert — then aged past its deadline behind the constraint's back.
            st.execute("INSERT INTO goal (id, member_id, name, target_amount, deadline, created_at, updated_at) "
                + "VALUES (1, 1, 'Expired trip', 5000, CURRENT_DATE + 30, NOW(), NOW())");
            st.execute("ALTER TABLE goal DROP CONSTRAINT chk_goal_deadline");
            st.execute("UPDATE goal SET deadline = CURRENT_DATE - 30 WHERE id = 1");
            // NOT VALID on purpose, and it is not a shortcut: it reproduces production exactly.
            // A plain ADD CONSTRAINT re-checks existing rows and would refuse the aged one — which
            // is itself the proof that no expired goal can ever be *written*. In production the
            // row aged past its deadline while the constraint sat there unvalidated, and the
            // constraint then blocked the next UPDATE. That is the state being restored here.
            st.execute("ALTER TABLE goal ADD CONSTRAINT chk_goal_deadline "
                + "CHECK (deadline > CURRENT_DATE) NOT VALID");

            // A goal still in the future, as a negative control.
            st.execute("INSERT INTO goal (id, member_id, name, target_amount, deadline, created_at, updated_at) "
                + "VALUES (2, 1, 'Future trip', 8000, CURRENT_DATE + 365, NOW(), NOW())");

            // The seeds set ids explicitly, so the BIGSERIAL sequence is still at 1 and the next
            // generated insert would collide.
            st.execute("SELECT setval('goal_id_seq', (SELECT MAX(id) FROM goal))");
        }
    }

    @Test
    @Order(1)
    void theBugIsRealBeforeTheMigration() throws SQLException {
        // Establishes that the fix is fixing something: touching the expired goal fails while
        // the old constraint is still in place.
        try (Connection c = connection(); Statement st = c.createStatement()) {
            assertThatThrownBy(() -> st.execute("UPDATE goal SET name = 'renamed' WHERE id = 1"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("chk_goal_deadline");
        }
    }

    @Test
    @Order(2)
    void anExpiredGoalCanBeUpdatedAgainAfterTheMigration() throws SQLException {
        migrateTo("85");

        try (Connection c = connection(); Statement st = c.createStatement()) {
            assertThatCode(() -> st.execute("UPDATE goal SET name = 'renamed' WHERE id = 1"))
                .doesNotThrowAnyException();

            try (ResultSet rs = st.executeQuery("SELECT name, type FROM goal WHERE id = 1")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("name")).isEqualTo("renamed");
                // Existing rows keep meaning what they meant.
                assertThat(rs.getString("type")).isEqualTo("SAVINGS_TARGET");
            }
        }
    }

    @Test
    @Order(3)
    void aRecurringPlanNeedsNoTargetAndNoDeadline() throws SQLException {
        migrateTo("85");

        try (Connection c = connection(); Statement st = c.createStatement()) {
            assertThatCode(() -> st.execute(
                "INSERT INTO goal (member_id, name, type, monthly_amount, created_at, updated_at) "
                    + "VALUES (1, 'PEA monthly', 'RECURRING_INVESTMENT', 300, NOW(), NOW())"))
                .doesNotThrowAnyException();
        }
    }

    @Test
    @Order(4)
    void aSavingsTargetStillCannotBeCreatedWithoutATarget() throws SQLException {
        migrateTo("85");

        try (Connection c = connection(); Statement st = c.createStatement()) {
            // Dropping the NOT NULLs without the per-type CHECK would have allowed exactly this.
            assertThatThrownBy(() -> st.execute(
                "INSERT INTO goal (member_id, name, type, created_at, updated_at) "
                    + "VALUES (1, 'Empty', 'SAVINGS_TARGET', NOW(), NOW())"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("ck_goal_type_fields");
        }
    }
}
