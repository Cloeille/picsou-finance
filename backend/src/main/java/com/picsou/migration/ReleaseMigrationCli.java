package com.picsou.migration;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import com.picsou.config.LegacyMigrationRenumberingCallback;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.MappedSuperclass;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.output.ValidateOutput;
import org.flywaydb.core.api.output.ValidateResult;
import org.flywaydb.core.internal.exception.FlywayMigrateException;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.jpa.HibernatePersistenceProvider;
import org.hibernate.tool.schema.spi.SchemaManagementException;
import org.hibernate.type.SqlTypes;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.orm.jpa.hibernate.SpringImplicitNamingStrategy;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.util.ClassUtils;

import javax.sql.DataSource;
import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Standalone, Spring-free entry point for release-time schema migration and verification. */
public final class ReleaseMigrationCli {

    private static final String MIGRATION_LOCATION = "classpath:db/migration";
    private static final String MODEL_PACKAGE = "com.picsou.model";
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
            fail("Migration command failed; database state was not accepted.");
            diagnose(args[0], failure).forEach(System.err::println);
            return 1;
        }
    }

    /**
     * Builds an allow-listed diagnosis. Exception messages from JDBC, Flyway and Spring can carry
     * the JDBC URL, user name or SQL text, so only fields known to be safe are printed: our own
     * fixed messages, migration versions and script names, Flyway error codes, Hibernate's
     * schema-validation message (table and column names), SQLState codes and class names.
     */
    static List<String> diagnose(String command, Throwable failure) {
        List<String> lines = new ArrayList<>();
        List<String> chain = new ArrayList<>();
        String sqlState = null;
        for (Throwable cause = failure; cause != null && chain.size() < 10; cause = cause.getCause()) {
            chain.add(cause.getClass().getSimpleName());
            if (cause instanceof CliCheckException
                    || cause instanceof LegacyMigrationRenumberingCallback.DuplicateExternalAccountsException) {
                lines.add("Reason: " + cause.getMessage());
            } else if (cause instanceof FlywayMigrateException migrate && migrate.getMigration() != null) {
                MigrationInfo migration = migrate.getMigration();
                lines.add("Failing migration: " + describe(migration.getVersion() == null ? null
                    : migration.getVersion().getVersion(), migration.getScript()));
            } else if (cause instanceof SchemaManagementException schema) {
                lines.add("Schema validation: " + schema.getMessage());
            }
            if (cause instanceof FlywayException flyway && flyway.getErrorCode() != null) {
                lines.add("Flyway error code: " + flyway.getErrorCode());
            }
            if (sqlState == null && cause instanceof SQLException sql && sql.getSQLState() != null) {
                sqlState = sql.getSQLState();
            }
        }
        if (sqlState != null) {
            lines.add("SQLState: " + sqlState);
        }
        lines.add(0, "Command: " + command + "; cause: " + String.join(" <- ", chain));
        return lines.stream().distinct().toList();
    }

    private static String describe(String version, String script) {
        String file = script == null || script.isBlank() ? "no packaged script"
            : script.substring(Math.max(script.lastIndexOf('/'), script.lastIndexOf('\\')) + 1);
        return version == null ? file : "V" + version + " (" + file + ")";
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
            throw new CliCheckException("Schema baseline " + baseline.getVersion().getVersion()
                + " is newer than the available migrations");
        }
    }

    private static void verify(Flyway flyway) {
        ValidateResult validation = flyway.validateWithResult();
        if (!validation.validationSuccessful) {
            List<String> problems = new ArrayList<>();
            for (ValidateOutput invalid : validation.invalidMigrations) {
                String code = invalid.errorDetails == null ? "unknown" : String.valueOf(invalid.errorDetails.errorCode);
                problems.add(describe(invalid.version, invalid.filepath) + " " + code);
            }
            if (problems.isEmpty() && validation.errorDetails != null) {
                problems.add(String.valueOf(validation.errorDetails.errorCode));
            }
            throw new CliCheckException("Flyway validation failed: " + String.join("; ", problems));
        }
        for (MigrationInfo migration : flyway.info().all()) {
            String state = migration.getState().toString();
            if (!"SUCCESS".equals(state) && !"OUT_OF_ORDER".equals(state)) {
                throw new CliCheckException("Migration inventory is incomplete: " + describe(
                    migration.getVersion() == null ? null : migration.getVersion().getVersion(),
                    migration.getScript()) + " is " + state);
            }
        }
    }

    /** Hibernate settings Spring Boot applies by default; the CLI must validate the same mapping. */
    static Map<String, Object> jpaProperties() {
        return Map.of(
            AvailableSettings.HBM2DDL_AUTO, "validate",
            AvailableSettings.DEFAULT_SCHEMA, "public",
            AvailableSettings.PHYSICAL_NAMING_STRATEGY, CamelCaseToUnderscoresNamingStrategy.class.getName(),
            AvailableSettings.IMPLICIT_NAMING_STRATEGY, SpringImplicitNamingStrategy.class.getName());
    }

    private static void validatePhysicalSchema(DataSource dataSource) throws SQLException {
        LocalContainerEntityManagerFactoryBean factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(dataSource);
        factory.setPackagesToScan(MODEL_PACKAGE);
        factory.setPersistenceProviderClass(HibernatePersistenceProvider.class);
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setJpaPropertyMap(jpaProperties());

        EntityManagerFactory entityManagerFactory = null;
        try {
            factory.afterPropertiesSet();
            entityManagerFactory = factory.getObject();
            if (entityManagerFactory == null) {
                throw new CliCheckException("Persistence unit was not created");
            }
            validateNamedEnums(dataSource);
        } finally {
            if (entityManagerFactory != null) {
                entityManagerFactory.close();
            }
            factory.destroy();
        }
    }

    /**
     * Hibernate {@code validate} does not compare native enum labels, so a missing
     * {@code ALTER TYPE ... ADD VALUE} passes it and fails later at runtime.
     */
    private static void validateNamedEnums(DataSource dataSource) throws SQLException {
        Map<String, Set<String>> required = namedEnumLabels();
        if (!required.containsKey("account_type")) {
            // Fail closed: an empty scan (e.g. a packaging change) must not silently skip the check.
            throw new CliCheckException("Named enum discovery did not find account_type");
        }
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                 "SELECT enumlabel FROM pg_enum WHERE enumtypid = to_regtype(?)")) {
            for (Map.Entry<String, Set<String>> type : required.entrySet()) {
                Set<String> labels = new LinkedHashSet<>();
                statement.setString(1, "public." + type.getKey());
                try (ResultSet results = statement.executeQuery()) {
                    while (results.next()) {
                        labels.add(results.getString(1));
                    }
                }
                Set<String> missing = new TreeSet<>(type.getValue());
                missing.removeAll(labels);
                if (!missing.isEmpty()) {
                    throw new CliCheckException("PostgreSQL enum " + type.getKey() + " is missing labels " + missing);
                }
            }
        }
    }

    /** Maps every PostgreSQL named enum used by an entity to the Java constants it must accept. */
    static Map<String, Set<String>> namedEnumLabels() {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Entity.class));
        scanner.addIncludeFilter(new AnnotationTypeFilter(Embeddable.class));
        scanner.addIncludeFilter(new AnnotationTypeFilter(MappedSuperclass.class));
        Map<String, Set<String>> required = new TreeMap<>();
        ClassLoader loader = ReleaseMigrationCli.class.getClassLoader();
        for (BeanDefinition candidate : scanner.findCandidateComponents(MODEL_PACKAGE)) {
            Class<?> type = ClassUtils.resolveClassName(candidate.getBeanClassName(), loader);
            for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
                for (Field field : current.getDeclaredFields()) {
                    JdbcTypeCode jdbcType = field.getAnnotation(JdbcTypeCode.class);
                    if (jdbcType == null || jdbcType.value() != SqlTypes.NAMED_ENUM) {
                        continue;
                    }
                    Column column = field.getAnnotation(Column.class);
                    if (!field.getType().isEnum() || column == null || column.columnDefinition().isBlank()) {
                        throw new CliCheckException("Named enum mapping without a PostgreSQL type: "
                            + current.getSimpleName() + "." + field.getName());
                    }
                    Set<String> labels = required.computeIfAbsent(column.columnDefinition(), key -> new TreeSet<>());
                    Arrays.stream(field.getType().getEnumConstants()).map(value -> ((Enum<?>) value).name())
                        .forEach(labels::add);
                }
            }
        }
        return required;
    }

    /** A check failure whose message is built only from code constants and migration names. */
    static final class CliCheckException extends IllegalStateException {
        CliCheckException(String message) {
            super(message);
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
