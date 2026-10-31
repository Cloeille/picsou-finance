package com.picsou.config;

import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.flywaydb.core.api.configuration.Configuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Moves the schema-history rows written under the 1.1.0 branch's old numbering to the versions
 * their files carry since commit 2839c94 (V80, V81, V86-V88 became V93-V99), before Flyway
 * validates the history.
 *
 * <p>Without it, a database that applied the old files fails validation (V80/V86-V88 no longer
 * match the files now at those versions, V81 has no file) and would then re-run V93-V99 on
 * objects that already exist. A row is matched on old version, description and checksum
 * together: V80 and V86-V88 are also the versions of unrelated migrations, so a version alone
 * identifies nothing. A row is left alone when its new version is already in the history, which
 * makes the callback a no-op on fresh and already-renumbered databases.
 *
 * <p>Spring Boot registers every {@link Callback} bean with the auto-configured Flyway. Flyway
 * runs the callback in a transaction. Operator upgrade path: docs/features/docker-deployment.md.
 */
@Component
public class LegacyMigrationRenumberingCallback implements Callback {

    private static final Logger log = LoggerFactory.getLogger(LegacyMigrationRenumberingCallback.class);

    private record Renumbering(String oldVersion, String newVersion, String description, int checksum) {
        String script() {
            return "V" + newVersion + "__" + description.replace(' ', '_') + ".sql";
        }
    }

    // 2839c94 renamed the files without touching their content, so the checksum recorded under
    // the old version is the checksum of the file at the new one.
    private static final List<Renumbering> RENUMBERINGS = List.of(
        new Renumbering("80", "93", "fortuneo session", -1569596521),
        new Renumbering("80", "94", "backfill tr crypto transaction tickers", 2050086936),
        new Renumbering("81", "95", "fortuneo integration", -76224459),
        new Renumbering("81", "96", "backfill trade republic valuations", 664629777),
        new Renumbering("86", "97", "security profile isin", 1417570777),
        new Renumbering("87", "98", "security profile status", -797426580),
        new Renumbering("88", "99", "security profile fund facts", 140413632)
    );

    @Override
    public boolean supports(Event event, Context context) {
        // BEFORE_MIGRATE covers validate-on-migrate=false; after a validation it finds nothing left.
        return event == Event.BEFORE_VALIDATE || event == Event.BEFORE_MIGRATE;
    }

    @Override
    public boolean canHandleInTransaction(Event event, Context context) {
        return true;
    }

    @Override
    public void handle(Event event, Context context) {
        try {
            renumber(context.getConnection(), context.getConfiguration());
        } catch (SQLException e) {
            throw new FlywayException("Could not renumber the legacy schema-history rows", e);
        }
    }

    @Override
    public String getCallbackName() {
        return "legacy-migration-renumbering";
    }

    private static void renumber(Connection connection, Configuration configuration) throws SQLException {
        String schema = historySchema(connection, configuration);
        if (!historyTableExists(connection, schema, configuration.getTable())) {
            return;
        }
        String table = quote(schema) + "." + quote(configuration.getTable());
        String sql = "UPDATE " + table + " SET \"version\" = ?, \"script\" = ?"
            + " WHERE \"version\" = ? AND \"description\" = ? AND \"checksum\" = ?"
            + " AND NOT EXISTS (SELECT 1 FROM " + table + " WHERE \"version\" = ?)";

        List<String> renumbered = new ArrayList<>();
        try (PreparedStatement update = connection.prepareStatement(sql)) {
            for (Renumbering r : RENUMBERINGS) {
                update.setString(1, r.newVersion());
                update.setString(2, r.script());
                update.setString(3, r.oldVersion());
                update.setString(4, r.description());
                update.setInt(5, r.checksum());
                update.setString(6, r.newVersion());
                if (update.executeUpdate() > 0) {
                    renumbered.add("V" + r.oldVersion() + " '" + r.description() + "' -> V" + r.newVersion());
                }
            }
        }
        if (!renumbered.isEmpty()) {
            log.info("Renumbered {} legacy Flyway schema-history row(s) (issue #174): {}",
                renumbered.size(), String.join(", ", renumbered));
        }
    }

    private static String historySchema(Connection connection, Configuration configuration) throws SQLException {
        if (configuration.getDefaultSchema() != null) {
            return configuration.getDefaultSchema();
        }
        if (configuration.getSchemas().length > 0) {
            return configuration.getSchemas()[0];
        }
        return connection.getSchema();
    }

    private static boolean historyTableExists(Connection connection, String schema, String table) throws SQLException {
        try (ResultSet tables = connection.getMetaData().getTables(null, schema, table, null)) {
            return tables.next();
        }
    }

    private static String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }
}
