package com.picsou.migration;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import com.picsou.config.LegacyMigrationRenumberingCallback;
import com.picsou.model.AccountType;
import jakarta.persistence.EntityManagerFactory;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.hibernate.jpa.HibernatePersistenceProvider;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Standalone, Spring-free entry point for release-time schema migration and verification. */
public final class ReleaseMigrationCli {

    private static final String MIGRATION_LOCATION = "classpath:db/migration";
    private static final String[] QUIET_LOGGERS = {
        "org.flywaydb", "org.hibernate", "org.springframework.orm.jpa"
    };

    private ReleaseMigrationCli() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.getenv()));
    }

    static int run(String[] args, Map<String, String> environment) {
        if (args == null || args.length != 1) {
            return fail("Usage: ReleaseMigrationCli migrate|verify");
        }
        if (!"migrate".equals(args[0]) && !"verify".equals(args[0])) {
            return fail("Command must be migrate or verify.");
        }

        String url = environment.get("SPRING_DATASOURCE_URL");
        String username = environment.get("SPRING_DATASOURCE_USERNAME");
        String password = environment.get("SPRING_DATASOURCE_PASSWORD");
        if (isBlank(url) || isBlank(username) || isBlank(password)) {
            return fail("SPRING_DATASOURCE_URL, SPRING_DATASOURCE_USERNAME and SPRING_DATASOURCE_PASSWORD are required.");
        }

        try (QuietCliLogging ignored = QuietCliLogging.suppress()) {
            DataSource dataSource = new DriverManagerDataSource(url, username, password);
            Flyway flyway = configure(url, username, password, "migrate".equals(args[0]));
            if ("migrate".equals(args[0])) {
                rejectHighBaseline(flyway);
                flyway.migrate();
            } else {
                verify(flyway);
            }
            validatePhysicalSchema(dataSource);
            return 0;
        } catch (RuntimeException | SQLException failure) {
            return fail("Migration command failed; database state was not accepted.");
        }
    }

    private static Flyway configure(String url, String username, String password, boolean migrate) {
        var configuration = Flyway.configure()
            .dataSource(url, username, password)
            .locations(MIGRATION_LOCATION)
            .outOfOrder(true)
            .baselineOnMigrate(false)
            .cleanDisabled(true)
            .validateOnMigrate(true)
            .ignoreMigrationPatterns(new String[0])
            .loggers("slf4j");
        if (migrate) {
            configuration.callbacks(new LegacyMigrationRenumberingCallback());
        }
        return configuration.load();
    }

    private static void rejectHighBaseline(Flyway flyway) {
        MigrationInfo[] migrations = flyway.info().all();
        MigrationInfo baseline = Arrays.stream(migrations)
            .filter(migration -> "BASELINE".equals(migration.getType().toString()))
            .findFirst().orElse(null);
        if (baseline == null) {
            return;
        }
        var latestResolved = Arrays.stream(migrations)
            .filter(migration -> migration.getVersion() != null)
            .filter(migration -> !"BASELINE".equals(migration.getType().toString()))
            .map(MigrationInfo::getVersion)
            .max(org.flywaydb.core.api.MigrationVersion::compareTo)
            .orElse(null);
        if (latestResolved != null && baseline.getVersion().compareTo(latestResolved) > 0) {
            throw new IllegalStateException("Schema baseline is newer than the available migrations");
        }
    }

    private static void verify(Flyway flyway) {
        flyway.validate();
        for (MigrationInfo migration : flyway.info().all()) {
            String state = migration.getState().toString();
            if (!"SUCCESS".equals(state) && !"OUT_OF_ORDER".equals(state)) {
                throw new IllegalStateException("Migration inventory is incomplete");
            }
        }
    }

    private static void validatePhysicalSchema(DataSource dataSource) throws SQLException {
        LocalContainerEntityManagerFactoryBean factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(dataSource);
        factory.setPackagesToScan("com.picsou.model");
        factory.setPersistenceProviderClass(HibernatePersistenceProvider.class);
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setJpaPropertyMap(Map.of(
            "hibernate.hbm2ddl.auto", "validate",
            "hibernate.default_schema", "public"));

        EntityManagerFactory entityManagerFactory = null;
        try {
            factory.afterPropertiesSet();
            entityManagerFactory = factory.getObject();
            if (entityManagerFactory == null) {
                throw new IllegalStateException("Persistence unit was not created");
            }
            validateAccountType(dataSource);
        } finally {
            if (entityManagerFactory != null) {
                entityManagerFactory.close();
            }
            factory.destroy();
        }
    }

    private static void validateAccountType(DataSource dataSource) throws SQLException {
        Set<String> labels = new HashSet<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                 "SELECT enumlabel FROM pg_enum WHERE enumtypid = ?::regtype")) {
            statement.setString(1, "public.account_type");
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    labels.add(results.getString(1));
                }
            }
        }
        Set<String> expected = Arrays.stream(AccountType.values()).map(Enum::name)
            .collect(HashSet::new, Set::add, Set::addAll);
        if (!labels.containsAll(expected)) {
            throw new IllegalStateException("PostgreSQL account_type enum is incomplete");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static int fail(String message) {
        System.err.println(message);
        return 1;
    }

    /** Changes only CLI-time log levels and restores test/application state on every exit path. */
    private static final class QuietCliLogging implements AutoCloseable {
        private final LoggerContext context;
        private final Level[] previousLevels;

        private QuietCliLogging(LoggerContext context, Level[] previousLevels) {
            this.context = context;
            this.previousLevels = previousLevels;
        }

        static QuietCliLogging suppress() {
            LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
            Level[] previous = new Level[QUIET_LOGGERS.length];
            for (int i = 0; i < QUIET_LOGGERS.length; i++) {
                Logger logger = context.getLogger(QUIET_LOGGERS[i]);
                previous[i] = logger.getLevel();
                logger.setLevel(Level.OFF);
            }
            return new QuietCliLogging(context, previous);
        }

        @Override
        public void close() {
            for (int i = 0; i < QUIET_LOGGERS.length; i++) {
                context.getLogger(QUIET_LOGGERS[i]).setLevel(previousLevels[i]);
            }
        }
    }
}
