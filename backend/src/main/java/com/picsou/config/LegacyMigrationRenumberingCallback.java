package com.picsou.config;

import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationVersion;
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
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Rewrites schema-history rows written under an older numbering to the version and checksum their
 * file carries today, before Flyway validates the history. Two renumberings are covered:
 *
 * <ul>
 *   <li>Issue #174: commit 2839c94 moved the 1.1.0 branch's V80, V81 and V86-V88 to V93-V99.</li>
 *   <li>Issue #265: merging main into 1.1.0 (1906d3a) moved the 1.0.11-1.0.18 releases' V38 and
 *       V54-V64 to V56-V65, V94 and V96, and added comments to V51 and V55-V58.</li>
 * </ul>
 *
 * <p>Without it, such a database fails validation, and would then re-run the moved files on
 * objects that already exist. A row is matched on old version, description and checksum
 * together: the old versions are also the versions of unrelated migrations, so a version alone
 * identifies nothing. A row is left alone when another row already holds its new version, which
 * makes the callback a no-op on fresh and already-renumbered databases.
 *
 * <p>It also stops the migration while V47 is pending and active accounts share an
 * {@code external_account_id} and member. V47 deletes all but the newest of each such group, and
 * their history cascades with them. Databases that skipped V47 are the 1.0.x line, which never
 * enforced that key; the operator resolves the duplicates first (upgrade note below). That check
 * commits before any migration runs, while the old instance may still be syncing, so it runs again
 * in V47's own transaction with account writes locked out until V47 commits.
 *
 * <p>Spring Boot registers every {@link Callback} bean with the auto-configured Flyway. Flyway
 * runs the callback in a transaction. Operator upgrade path: docs/features/docker-deployment.md.
 */
@Component
public class LegacyMigrationRenumberingCallback implements Callback {

    private static final Logger log = LoggerFactory.getLogger(LegacyMigrationRenumberingCallback.class);

    /**
     * One applied file that now lives under {@code newVersion}. {@code newChecksum} differs from
     * {@code checksum} only when the file gained comments; its SQL is unchanged.
     */
    private record Renumbering(String oldVersion, String newVersion, String description,
                               int checksum, int newChecksum) {
        Renumbering(String oldVersion, String newVersion, String description, int checksum) {
            this(oldVersion, newVersion, description, checksum, checksum);
        }

        String script() {
            return "V" + newVersion + "__" + description.replace(' ', '_') + ".sql";
        }
    }

    // Applied in list order, inside one transaction. A move whose target still holds an older row
    // of the same chain must come after the move that frees it: V54 -> V56 waits for V56 -> V58.
    private static final List<Renumbering> RENUMBERINGS = List.of(
        // #174: 2839c94 renamed the files without touching their content.
        new Renumbering("80", "93", "fortuneo session", -1569596521),
        new Renumbering("80", "94", "backfill tr crypto transaction tickers", 2050086936),
        new Renumbering("81", "95", "fortuneo integration", -76224459),
        new Renumbering("81", "96", "backfill trade republic valuations", 664629777),
        new Renumbering("86", "97", "security profile isin", 1417570777),
        new Renumbering("87", "98", "security profile status", -797426580),
        new Renumbering("88", "99", "security profile fund facts", 140413632),
        // #265: the 1.0.x releases, highest old version first.
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
        new Renumbering("38", "94", "backfill tr crypto transaction tickers", 2050086936)
    );

    private static final MigrationVersion V47 = MigrationVersion.fromVersion("47");

    @Override
    public boolean supports(Event event, Context context) {
        // BEFORE_MIGRATE covers validate-on-migrate=false; after a validation it finds nothing left.
        return event == Event.BEFORE_VALIDATE || event == Event.BEFORE_MIGRATE
            || event == Event.BEFORE_EACH_MIGRATE;
    }

    @Override
    public boolean canHandleInTransaction(Event event, Context context) {
        return true;
    }

    @Override
    public void handle(Event event, Context context) {
        int duplicateGroups;
        try {
            if (event == Event.BEFORE_EACH_MIGRATE) {
                if (!V47.equals(context.getMigrationInfo().getVersion())) {
                    return;
                }
                duplicateGroups = lockAccountsForV47(context.getConnection(), context.getConfiguration());
            } else {
                renumber(context.getConnection(), context.getConfiguration());
                duplicateGroups = duplicateGroupsBeforeV47(context.getConnection(), context.getConfiguration());
            }
        } catch (SQLException e) {
            throw new FlywayException("Could not renumber the legacy schema-history rows", e);
        }
        if (duplicateGroups > 0) {
            throw new DuplicateExternalAccountsException(duplicateGroups);
        }
    }

    /** Fixed text and a count only, so the release CLI can print it without leaking data. */
    public static final class DuplicateExternalAccountsException extends FlywayException {
        DuplicateExternalAccountsException(int groups) {
            super(groups + " group(s) of active accounts share an external_account_id and member."
                + " Pending migration V47 would delete all but the newest account of each group, with its"
                + " history. Soft-delete the extra accounts or change their external_account_id, then start"
                + " again. See docs/features/docker-deployment.md#upgrading-a-10x-database-to-110");
        }
    }

    @Override
    public String getCallbackName() {
        return "legacy-migration-renumbering";
    }

    private static void renumber(Connection connection, Configuration configuration) throws SQLException {
        String schema = historySchema(connection, configuration);
        if (!tableExists(connection, schema, configuration.getTable())) {
            return;
        }
        String table = quote(schema) + "." + quote(configuration.getTable());
        String sql = "UPDATE " + table + " AS h SET \"version\" = ?, \"script\" = ?, \"checksum\" = ?"
            + " WHERE h.\"version\" = ? AND h.\"description\" = ? AND h.\"checksum\" = ?"
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
                update.setInt(6, r.checksum());
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

    /** Groups V47 would collapse, or 0 when V47 will not run or the account columns are not there yet. */
    private static int duplicateGroupsBeforeV47(Connection connection, Configuration configuration)
            throws SQLException {
        String schema = historySchema(connection, configuration);
        if (!tableExists(connection, schema, configuration.getTable())
                || !columnExists(connection, schema, "account", "external_account_id")) {
            return 0;
        }
        String history = quote(schema) + "." + quote(configuration.getTable());
        // A baseline at or above V47 means Flyway never runs it.
        try (PreparedStatement applied = connection.prepareStatement(
                "SELECT 1 FROM " + history + " WHERE \"version\" = '47' OR CASE WHEN \"type\" = 'BASELINE'"
                    + " AND \"version\" ~ '^[0-9]+$' THEN \"version\"::int >= 47 ELSE false END");
             ResultSet rows = applied.executeQuery()) {
            if (rows.next()) {
                return 0;
            }
        }
        return duplicateGroups(connection, schema);
    }

    /**
     * Runs in V47's own transaction, just before its DELETE. The up-front check has already
     * committed, and a running 1.0.x instance can still sync a duplicate in. The lock blocks
     * account writes until V47 commits, so the groups counted here are the groups V47 sees.
     */
    private static int lockAccountsForV47(Connection connection, Configuration configuration) throws SQLException {
        String schema = historySchema(connection, configuration);
        try (Statement lock = connection.createStatement()) {
            lock.execute("LOCK TABLE " + quote(schema) + ".account IN SHARE ROW EXCLUSIVE MODE");
        }
        return duplicateGroups(connection, schema);
    }

    private static int duplicateGroups(Connection connection, String schema) throws SQLException {
        // Before V20 (member_id) and V30 (deleted_at) every account is one member's and active.
        String member = columnExists(connection, schema, "account", "member_id") ? ", member_id" : "";
        String active = columnExists(connection, schema, "account", "deleted_at") ? " AND deleted_at IS NULL" : "";
        try (PreparedStatement duplicates = connection.prepareStatement(
                "SELECT count(*) FROM (SELECT 1 FROM " + quote(schema) + ".account"
                    + " WHERE external_account_id IS NOT NULL" + active
                    + " GROUP BY external_account_id" + member + " HAVING count(*) > 1) d");
             ResultSet rows = duplicates.executeQuery()) {
            rows.next();
            return rows.getInt(1);
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

    private static boolean tableExists(Connection connection, String schema, String table) throws SQLException {
        try (ResultSet tables = connection.getMetaData().getTables(null, schema, table, null)) {
            return tables.next();
        }
    }

    private static boolean columnExists(Connection connection, String schema, String table, String column)
            throws SQLException {
        try (ResultSet columns = connection.getMetaData().getColumns(null, schema, table, column)) {
            return columns.next();
        }
    }

    private static String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }
}
