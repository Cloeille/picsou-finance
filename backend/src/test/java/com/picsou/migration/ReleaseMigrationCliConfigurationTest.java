package com.picsou.migration;

import org.hibernate.cfg.AvailableSettings;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateProperties;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateSettings;
import org.springframework.boot.autoconfigure.orm.jpa.JpaProperties;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Docker-free checks that the release CLI validates what the running application maps. */
class ReleaseMigrationCliConfigurationTest {

    @Test
    void usesSpringBootDefaultNamingStrategies() {
        Map<String, Object> boot = new HibernateProperties().determineHibernateProperties(
            new JpaProperties().getProperties(), new HibernateSettings());
        Map<String, Object> cli = ReleaseMigrationCli.jpaProperties();

        for (String key : new String[]{AvailableSettings.PHYSICAL_NAMING_STRATEGY,
                                       AvailableSettings.IMPLICIT_NAMING_STRATEGY}) {
            assertThat(boot).containsKey(key);
            assertThat(String.valueOf(cli.get(key))).isEqualTo(String.valueOf(boot.get(key)));
        }
    }

    @Test
    void discoversEveryPostgresNamedEnumMappedByAnEntity() {
        Map<String, java.util.Set<String>> enums = ReleaseMigrationCli.namedEnumLabels();

        assertThat(enums.keySet()).containsExactlyInAnyOrder(
            "account_type", "category_kind", "rate_basis", "recurring_cadence", "recurring_status",
            "requisition_status", "savings_product", "sharing_level");
        assertThat(enums.get("account_type")).contains("CREDIT_CARD");
        assertThat(enums.get("rate_basis")).containsExactlyInAnyOrder("GROSS", "NET");
    }

    @Test
    void unreachableDatabaseDiagnosisOmitsConnectionDetails() {
        String url = "jdbc:postgresql://127.0.0.1:1/unreachable_db_marker";
        PrintStream original = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        int status;
        try (PrintStream capture = new PrintStream(captured, true, StandardCharsets.UTF_8)) {
            System.setErr(capture);
            status = ReleaseMigrationCli.run(new String[]{"verify"}, Map.of(
                "SPRING_DATASOURCE_URL", url,
                "SPRING_DATASOURCE_USERNAME", "user_marker",
                "SPRING_DATASOURCE_PASSWORD", "sentinel-password-marker"));
        } finally {
            System.setErr(original);
        }
        String output = captured.toString(StandardCharsets.UTF_8);

        assertThat(status).isNotZero();
        assertThat(output).contains("Command: verify; cause: ", "SQLState: 08001")
            .doesNotContain("unreachable_db_marker", "127.0.0.1", "user_marker", "sentinel-password-marker");
    }
}
