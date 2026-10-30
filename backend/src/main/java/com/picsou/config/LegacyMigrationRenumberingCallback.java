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
 * Moves the schema-history rows written under an old numbering to the versions their files carry
 * now, before Flyway validates the history: the 1.1.0 branch's V80, V81, V86-V88, which commit
 * 2839c94 moved to V93-V99, and the 1.0.x line's V38 and V54-V64, which the 1.1.0 merge moved to
 * V56-V65, V94 and V96. It also restamps the files edited after release (V51, V47) with their
 * current checksum.
 *
 * <p>Without it, a database that applied the old files fails validation (the old versions no
 * longer match the files now at those versions, or have no file) and would then re-run the moved
 * files on objects that already exist. A row is matched on old version, description and checksum
 * together: the old versions are also the versions of unrelated migrations, so a version alone
 * identifies nothing. A row is left alone when another row already holds its new version, which
 * makes the callback a no-op on fresh and already-renumbered databases.
 *
 * <p>Spring Boot registers every {@link Callback} bean with the auto-configured Flyway. Flyway
 * runs the callback in a transaction. Operator upgrade path: docs/features/docker-deployment.md.
 */
@Component
public class LegacyMigrationRenumberingCallback implements Callback {

    private static final Logger log = LoggerFactory.getLogger(LegacyMigrationRenumberingCallback.class);

    private record Renumbering(String oldVersion, String newVersion, String description,
                               int oldChecksum, int newChecksum) {
        Renumbering(String oldVersion, String newVersion, String description, int checksum) {
            this(oldVersion, newVersion, description, checksum, checksum);
        }

        String script() {
            return "V" + newVersion + "__" + description.replace(' ', '_') + ".sql";
        }
    }

    // 2839c94 renamed the files without touching their content, so the checksum recorded under
    // the old version is the checksum of the file at the new one.
    //
    // The 1.0.x line (1.0.11-1.0.18, issue #265) shipped files that the 1.1.0 merge moved up.
    // V51 and old V55-V58 also had only their comments edited, so their row takes the new
    // file's checksum. Moves run from the highest version down: each one frees the version the
    // next one moves to (old V64 leaves before old V62 takes its place).
    private static final List<Renumbering> RENUMBERINGS = List.of(
        new Renumbering("80", "93", "fortuneo session", -1569596521),
        new Renumbering("80", "94", "backfill tr crypto transaction tickers", 2050086936),
        new Renumbering("81", "95", "fortuneo integration", -76224459),
        new Renumbering("81", "96", "backfill trade republic valuations", 664629777),
        new Renumbering("86", "97", "security profile isin", 1417570777),
        new Renumbering("87", "98", "security profile status", -797426580),
        new Renumbering("88", "99", "security profile fund facts", 140413632),

        new Renumbering("64", "96", "backfill trade republic valuations", 664629777),
        new Renumbering("63", "65", "constrain bourse direct sync errors", -1764174252),
        new Renumbering("62", "64", "backfill bourse direct valuations", 88940041),
        new Renumbering("61", "63", "harden bourse direct sync", -840345283),
        new Renumbering("60", "62", "bourse direct integration", 236247500),
        new Renumbering("59", "61", "bourse direct session", 2053764575),
        new Renumbering("58", "60", "account cash balance", -694849918, -1212750843),
        new Renumbering("57", "59", "ibkr connection", 1520765453, -1382846890),
        new Renumbering("56", "58", "persistent session previous token", 720404896, 1251005780),
        new Renumbering("55", "57", "wallet evm account name", -1244807568, -1770899403),
        new Renumbering("54", "56", "wallet ethereum to evm", 903440536),
        new Renumbering("51", "51", "requisition oauth state", 1952390840, 960451194),
        new Renumbering("38", "94", "backfill tr crypto transaction tickers", 2050086936),

        // V47 used to delete every active account sharing an external id with a newer one, whatever
        // its provider or is_manual. A 1.0.x database runs it out of order, so it now merges only
        // genuine duplicates and creates V72's index. A 1.1.0 database already ran the old file;
        // V72 replaced that index, so the new checksum describes its schema too.
        new Renumbering("47", "47", "account external unique", -1024222822, 80293229)
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
        String sql = "UPDATE " + table + " AS h SET \"version\" = ?, \"script\" = ?, \"checksum\" = ?"
            + " WHERE \"version\" = ? AND \"description\" = ? AND \"checksum\" = ?"
            + " AND NOT EXISTS (SELECT 1 FROM " + table + " o"
            + " WHERE o.\"version\" = ? AND o.\"installed_rank\" <> h.\"installed_rank\")";

        List<String> renumbered = new ArrayList<>();
        try (PreparedStatement update = connection.prepareStatement(sql)) {
            for (Renumbering r : RENUMBERINGS) {
                update.setString(1, r.newVersion());
                update.setString(2, r.script());
                update.setInt(3, r.newChecksum());
                update.setString(4, r.oldVersion());
                update.setString(5, r.description());
                update.setInt(6, r.oldChecksum());
                update.setString(7, r.newVersion());
                if (update.executeUpdate() > 0) {
                    renumbered.add("V" + r.oldVersion() + " '" + r.description() + "' -> V" + r.newVersion());
                }
            }
        }
        if (!renumbered.isEmpty()) {
            log.info("Renumbered {} legacy Flyway schema-history row(s) (issues #174, #265): {}",
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
