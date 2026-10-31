package com.picsou.config;

import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Set;

/**
 * Keeps the accounts that V47 and V77 would delete on a 1.0.x database, which runs them out of
 * order on its first 1.1.0 start (#265). The scripts in db/out-of-order-guard run just before and
 * just after each of them, and say what they protect.
 *
 * <p>The migrations themselves stay as 1.1.0 shipped them: an edit changes their checksum, and a
 * 1.1.0 binary would then refuse to start on a database this version upgraded.
 *
 * <p>Flyway runs a per-migration callback in the migration's own transaction, so a failure rolls
 * the guard back with the migration.
 */
@Component
public class OutOfOrderAccountMigrationGuard implements Callback {

    private static final Set<String> GUARDED_VERSIONS = Set.of("47", "77");

    @Override
    public boolean supports(Event event, Context context) {
        if (event != Event.BEFORE_EACH_MIGRATE && event != Event.AFTER_EACH_MIGRATE) {
            return false;
        }
        MigrationInfo migration = context.getMigrationInfo();
        return migration != null && migration.getVersion() != null
            && GUARDED_VERSIONS.contains(migration.getVersion().getVersion());
    }

    @Override
    public boolean canHandleInTransaction(Event event, Context context) {
        return true;
    }

    @Override
    public void handle(Event event, Context context) {
        String script = "/db/out-of-order-guard/V" + context.getMigrationInfo().getVersion().getVersion()
            + (event == Event.BEFORE_EACH_MIGRATE ? "__before.sql" : "__after.sql");
        try (InputStream in = OutOfOrderAccountMigrationGuard.class.getResourceAsStream(script);
             Statement statement = context.getConnection().createStatement()) {
            statement.execute(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException | SQLException e) {
            throw new FlywayException("Could not run " + script, e);
        }
    }

    @Override
    public String getCallbackName() {
        return "out-of-order-account-migration-guard";
    }
}
