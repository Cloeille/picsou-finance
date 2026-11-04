package com.picsou.config;

import org.apache.commons.logging.impl.NoOpLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecretBootstrapTest {

    /** The secrets the app generates. postgres_password is only ever written from POSTGRES_PASSWORD. */
    private static final List<String> FILES = List.of("jwt_secret", "crypto_key");

    @TempDir
    Path root;

    private final SecretBootstrap bootstrap = new SecretBootstrap(supplier -> supplier.get());

    private Path secretsDir() {
        return root.resolve("data").resolve(".secrets");
    }

    private MockEnvironment run(MockEnvironment environment) {
        bootstrap.postProcessEnvironment(environment, new SpringApplication());
        return environment;
    }

    private MockEnvironment run(String... nameValuePairs) {
        MockEnvironment environment = new MockEnvironment().withProperty("PICSOU_SECRETS_DIR", secretsDir().toString());
        for (int i = 0; i < nameValuePairs.length; i += 2) {
            environment.setProperty(nameValuePairs[i], nameValuePairs[i + 1]);
        }
        return run(environment);
    }

    private String read(String file) throws IOException {
        return Files.readString(secretsDir().resolve(file));
    }

    private void seed(String file, String content) throws IOException {
        Files.createDirectories(secretsDir());
        Files.writeString(secretsDir().resolve(file), content);
    }

    private static int decodedLength(String base64) {
        return Base64.getDecoder().decode(base64).length;
    }

    private List<String> secretsDirEntries() throws IOException {
        try (var entries = Files.list(secretsDir())) {
            return entries.map(p -> p.getFileName().toString()).toList();
        }
    }

    // ─── generation ───────────────────────────────────────────────────────

    @Test
    void firstStart_generatesTwoSecretsWithExpectedSizesAndModes() throws IOException {
        MockEnvironment environment = run();

        assertThat(decodedLength(environment.getProperty("JWT_SECRET"))).isEqualTo(48);
        assertThat(decodedLength(environment.getProperty("CRYPTO_ENCRYPTION_KEY"))).isEqualTo(32);
        for (String file : FILES) {
            assertThat(Files.getPosixFilePermissions(secretsDir().resolve(file)))
                .isEqualTo(PosixFilePermissions.fromString("rw-------"));
        }
        assertThat(Files.getPosixFilePermissions(secretsDir()))
            .isEqualTo(PosixFilePermissions.fromString("rwx------"));
        assertThat(environment.getProperty("JWT_SECRET")).isEqualTo(read("jwt_secret"));
        assertThat(environment.getProperty("CRYPTO_ENCRYPTION_KEY")).isEqualTo(read("crypto_key"));
    }

    /** The app cannot set the database's password, so a generated one could only mismatch it. */
    @Test
    void firstStart_neverGeneratesTheDatabasePassword() {
        MockEnvironment environment = run();

        assertThat(environment.getProperty("POSTGRES_PASSWORD")).isNull();
        assertThat(environment.getPropertySources().get(SecretBootstrap.SOURCE_NAME).containsProperty("POSTGRES_PASSWORD"))
            .isFalse();
        assertThat(secretsDir().resolve("postgres_password")).doesNotExist();
    }

    @Test
    void firstStart_leavesNoTemporaryFileBehind() throws IOException {
        run();

        try (var entries = Files.list(secretsDir())) {
            assertThat(entries.map(p -> p.getFileName().toString()).toList())
                .containsExactlyInAnyOrderElementsOf(FILES);
        }
    }

    @Test
    void secondStart_reusesFilesByteForByte() throws IOException {
        MockEnvironment first = run();
        byte[] jwt = Files.readAllBytes(secretsDir().resolve("jwt_secret"));
        byte[] crypto = Files.readAllBytes(secretsDir().resolve("crypto_key"));

        MockEnvironment second = run();

        assertThat(Files.readAllBytes(secretsDir().resolve("jwt_secret"))).isEqualTo(jwt);
        assertThat(Files.readAllBytes(secretsDir().resolve("crypto_key"))).isEqualTo(crypto);
        assertThat(second.getProperty("JWT_SECRET")).isEqualTo(first.getProperty("JWT_SECRET"));
        assertThat(second.getProperty("CRYPTO_ENCRYPTION_KEY")).isEqualTo(first.getProperty("CRYPTO_ENCRYPTION_KEY"));
    }

    @Test
    void existingFile_isUsedWhenVariableIsUnset() throws IOException {
        seed("postgres_password", "fixture-postgres-password");

        MockEnvironment environment = run();

        assertThat(environment.getProperty("POSTGRES_PASSWORD")).isEqualTo("fixture-postgres-password");
    }

    @Test
    void existingFile_withTrailingNewlineFromTheShellEntrypointIsTrimmed() throws IOException {
        seed("postgres_password", "fixture-postgres-password\n");

        assertThat(run().getProperty("POSTGRES_PASSWORD")).isEqualTo("fixture-postgres-password");
    }

    @Test
    void emptyVariable_isTreatedAsUnset() throws IOException {
        seed("jwt_secret", "from-the-file-0123456789abcdef0123456789");

        MockEnvironment environment = run("JWT_SECRET", "");

        assertThat(environment.getProperty("JWT_SECRET")).isEqualTo("from-the-file-0123456789abcdef0123456789");
    }

    // ─── env var vs file ──────────────────────────────────────────────────

    @Test
    void variable_winsOverExistingDifferentFile_andFileIsLeftAlone() throws IOException {
        seed("jwt_secret", "old-file-value-0123456789abcdef0123456789");

        MockEnvironment environment = run("JWT_SECRET", "operator-value-0123456789abcdef0123456789");

        assertThat(environment.getProperty("JWT_SECRET")).isEqualTo("operator-value-0123456789abcdef0123456789");
        assertThat(read("jwt_secret")).isEqualTo("old-file-value-0123456789abcdef0123456789");
    }

    @Test
    void variable_isWrittenToFileOnlyWhenMissing() throws IOException {
        run("CRYPTO_ENCRYPTION_KEY", "operator-key");

        assertThat(read("crypto_key")).isEqualTo("operator-key");
        assertThat(Files.getPosixFilePermissions(secretsDir().resolve("crypto_key")))
            .isEqualTo(PosixFilePermissions.fromString("rw-------"));
    }

    @Test
    void variableSupplied_neverGeneratesThatSecret() throws IOException {
        MockEnvironment environment = run("JWT_SECRET", "operator-value-0123456789abcdef0123456789");

        assertThat(environment.getProperty("JWT_SECRET")).isEqualTo("operator-value-0123456789abcdef0123456789");
        assertThat(read("jwt_secret")).isEqualTo("operator-value-0123456789abcdef0123456789");
    }

    // ─── refusal ──────────────────────────────────────────────────────────

    @Test
    void emptyFile_refusesToStart_namingFileAndVariable_withoutAnySecret() throws IOException {
        run();
        String jwt = read("jwt_secret");
        String crypto = read("crypto_key");
        Files.writeString(secretsDir().resolve("crypto_key"), "");

        assertThatThrownBy(() -> run())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining(secretsDir().resolve("crypto_key").toString())
            .hasMessageContaining("CRYPTO_ENCRYPTION_KEY")
            .satisfies(ex -> assertThat(ex.getMessage()).doesNotContain(jwt).doesNotContain(crypto));
        assertThat(read("crypto_key")).isEmpty();
    }

    @Test
    void emptyFile_isIgnoredWhenTheVariableIsSet() throws IOException {
        seed("jwt_secret", "");

        MockEnvironment environment = run("JWT_SECRET", "operator-value-0123456789abcdef0123456789");

        assertThat(environment.getProperty("JWT_SECRET")).isEqualTo("operator-value-0123456789abcdef0123456789");
    }

    // ─── activation ───────────────────────────────────────────────────────

    @Test
    void blankSecretsDir_leavesTheEnvironmentUntouched() {
        for (String blank : new String[] {"", "   "}) {
            MockEnvironment environment = new MockEnvironment().withProperty("PICSOU_SECRETS_DIR", blank);
            int sources = environment.getPropertySources().size();

            run(environment);

            assertThat(environment.getPropertySources()).hasSize(sources);
            assertThat(environment.getProperty("JWT_SECRET")).isNull();
        }
    }

    @Test
    void unsetSecretsDir_leavesTheEnvironmentUntouched() {
        StandardEnvironment environment = new StandardEnvironment();
        int sources = environment.getPropertySources().size();

        bootstrap.postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getPropertySources()).hasSize(sources);
        assertThat(environment.getPropertySources().contains(SecretBootstrap.SOURCE_NAME)).isFalse();
    }

    @Test
    void secretsDir_isReadFromTheSpringPropertyToo() throws IOException {
        MockEnvironment environment = new MockEnvironment().withProperty("picsou.secrets-dir", secretsDir().toString());

        run(environment);

        assertThat(secretsDir().resolve("jwt_secret")).exists();
    }

    // ─── datasource password ──────────────────────────────────────────────
    //
    // SecretBootstrap does not set spring.datasource.password. application.yml reads
    // SPRING_DATASOURCE_PASSWORD, then POSTGRES_PASSWORD, so these tests boot a real Spring
    // environment (application.yml, command line, system properties, the environment) and bind
    // the property the way the datasource does.

    private static Map<String, Object> variables(String... nameValuePairs) {
        Map<String, Object> variables = new HashMap<>();
        for (int i = 0; i < nameValuePairs.length; i += 2) {
            variables.put(nameValuePairs[i], nameValuePairs[i + 1]);
        }
        return variables;
    }

    private Map<String, Object> variablesWithSecretsDir(String... nameValuePairs) {
        Map<String, Object> variables = variables(nameValuePairs);
        variables.put("PICSOU_SECRETS_DIR", secretsDir().toString());
        return variables;
    }

    /**
     * The datasource password after a real start-up, with {@code environment} as the OS
     * environment and {@code systemProperties} as the JVM's {@code -D} options.
     */
    private String datasourcePassword(Map<String, Object> environment, Map<String, Object> systemProperties,
                                      String... commandLine) {
        StandardEnvironment standard = new StandardEnvironment();
        standard.getPropertySources().replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
            new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, environment));
        standard.getPropertySources().replace(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME,
            new MapPropertySource(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME, systemProperties));
        try (var context = new SpringApplicationBuilder(Empty.class)
            .web(WebApplicationType.NONE)
            .environment(standard)
            .run(commandLine)) {
            return Binder.get(context.getEnvironment()).bind("spring.datasource.password", String.class).orElse(null);
        }
    }

    private String configFile(String content) {
        try {
            Path file = root.resolve("extra.properties");
            Files.writeString(file, content);
            return "file:" + file;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private String datasourcePassword(Map<String, Object> environment, String... commandLine) {
        return datasourcePassword(environment, Map.of(), commandLine);
    }

    /**
     * The bare-metal quick start: CONTRIBUTING.md starts PostgreSQL with the password picsou and
     * the user only exports PICSOU_SECRETS_DIR, so the datasource must keep application.yml's default.
     */
    @Test
    void secretsDirectoryAlone_keepsTheYamlDefaultPassword_andGeneratesOnlyTheAppSecrets() {
        assertThat(datasourcePassword(variablesWithSecretsDir())).isEqualTo("picsou");

        assertThat(secretsDir().resolve("postgres_password")).doesNotExist();
        assertThat(secretsDir().resolve("jwt_secret")).isNotEmptyFile();
        assertThat(secretsDir().resolve("crypto_key")).isNotEmptyFile();
    }

    @Test
    void persistedPostgresPassword_isUsedByTheDatasource() throws IOException {
        seed("postgres_password", "persisted-postgres-password");

        assertThat(datasourcePassword(variablesWithSecretsDir())).isEqualTo("persisted-postgres-password");
    }

    @Test
    void postgresPasswordVariable_isUsedByTheDatasource_andPersisted() throws IOException {
        String password = "p; $pecial'\"\\pass";

        assertThat(datasourcePassword(variablesWithSecretsDir("POSTGRES_PASSWORD", password))).isEqualTo(password);
        assertThat(read("postgres_password")).isEqualTo(password);
    }

    @Test
    void nonEmptySpringPasswordVariable_winsOverPostgresPassword() {
        assertThat(datasourcePassword(variablesWithSecretsDir(
            "POSTGRES_PASSWORD", "postgres-special; $value",
            "SPRING_DATASOURCE_PASSWORD", "spring-override"))).isEqualTo("spring-override");
    }

    @Test
    void emptySpringPasswordVariable_fallsBackToPostgresPassword() {
        assertThat(datasourcePassword(variablesWithSecretsDir(
            "POSTGRES_PASSWORD", "postgres-special; $value",
            "SPRING_DATASOURCE_PASSWORD", ""))).isEqualTo("postgres-special; $value");
    }

    @Test
    void emptySpringPasswordVariable_fallsBackToThePersistedPassword() throws IOException {
        seed("postgres_password", "persisted-postgres-password");

        assertThat(datasourcePassword(variablesWithSecretsDir("SPRING_DATASOURCE_PASSWORD", "")))
            .isEqualTo("persisted-postgres-password");
    }

    /**
     * Nothing resolved: the empty variable is treated as unset and the datasource gets the password
     * of a start with no variable at all, which pins the constant to application.yml's default.
     * (Without a secrets directory Spring maps the empty variable to an empty password.)
     */
    @Test
    void emptySpringPasswordVariable_withoutAnyPostgresPassword_fallsBackToTheYamlDefault() {
        assertThat(datasourcePassword(variablesWithSecretsDir("SPRING_DATASOURCE_PASSWORD", "")))
            .isEqualTo(SecretBootstrap.DEFAULT_DATASOURCE_PASSWORD)
            .isEqualTo(datasourcePassword(variables()));
        assertThat(secretsDir().resolve("postgres_password")).doesNotExist();
    }

    @Test
    void commandLinePassword_winsOverPostgresPassword() {
        assertThat(datasourcePassword(variablesWithSecretsDir("POSTGRES_PASSWORD", "from-the-environment"),
            "--spring.datasource.password=from-the-command-line")).isEqualTo("from-the-command-line");
    }

    @Test
    void systemPropertyPassword_winsOverPostgresPassword() {
        assertThat(datasourcePassword(variablesWithSecretsDir("POSTGRES_PASSWORD", "from-the-environment"),
            Map.of("spring.datasource.password", "from-d"))).isEqualTo("from-d");
    }

    @Test
    void springApplicationJsonPassword_winsOverPostgresPassword() {
        assertThat(datasourcePassword(variablesWithSecretsDir(
            "POSTGRES_PASSWORD", "from-the-environment",
            "SPRING_APPLICATION_JSON", "{\"spring\":{\"datasource\":{\"password\":\"from-json\"}}}")))
            .isEqualTo("from-json");
    }

    /** The empty-variable fallback sits below the command line and {@code -D}, not above them. */
    @Test
    void explicitPassword_winsOverTheEmptyVariableFallbackToo() {
        Map<String, Object> environment = variablesWithSecretsDir(
            "POSTGRES_PASSWORD", "from-the-environment", "SPRING_DATASOURCE_PASSWORD", "");

        assertThat(datasourcePassword(environment, "--spring.datasource.password=from-the-command-line"))
            .isEqualTo("from-the-command-line");
        assertThat(datasourcePassword(environment, Map.of("spring.datasource.password", "from-d")))
            .isEqualTo("from-d");
    }

    @Test
    void explicitPicsouPassword_isPreserved() {
        assertThat(datasourcePassword(variablesWithSecretsDir("POSTGRES_PASSWORD", "picsou"))).isEqualTo("picsou");
    }

    /** Compose writes POSTGRES_PASSWORD= for an unset variable; Compose itself then uses picsou. */
    @Test
    void emptyPostgresPasswordVariable_isTreatedAsUnset() throws IOException {
        assertThat(datasourcePassword(variablesWithSecretsDir("POSTGRES_PASSWORD", ""))).isEqualTo("picsou");
        assertThat(datasourcePassword(variables("POSTGRES_PASSWORD", ""))).isEqualTo("picsou");
        assertThat(datasourcePassword(variables("POSTGRES_PASSWORD", "", "SPRING_DATASOURCE_PASSWORD", "")))
            .isEqualTo("picsou");
        seed("postgres_password", "persisted-postgres-password");
        assertThat(datasourcePassword(variablesWithSecretsDir("POSTGRES_PASSWORD", "")))
            .isEqualTo("persisted-postgres-password");
    }

    /** The empty-variable fallback does not need a secrets directory. */
    @Test
    void emptySpringPasswordVariable_withoutASecretsDirectory_fallsBackToPostgresPassword() {
        assertThat(datasourcePassword(variables("SPRING_DATASOURCE_PASSWORD", "", "POSTGRES_PASSWORD", "from-compose")))
            .isEqualTo("from-compose");
        assertThat(datasourcePassword(variables("SPRING_DATASOURCE_PASSWORD", ""))).isEqualTo("picsou");
    }

    /** A config file's spring.datasource.password beats the empty variable it replaces. */
    @Test
    void emptySpringPasswordVariable_lostToAConfigFilePassword() {
        assertThat(datasourcePassword(variablesWithSecretsDir("SPRING_DATASOURCE_PASSWORD", "",
                "POSTGRES_PASSWORD", "from-compose"),
            "--spring.config.additional-location=" + configFile("spring.datasource.password=from-the-file")))
            .isEqualTo("from-the-file");
    }

    /** Without a secrets directory SecretBootstrap does nothing: application.yml alone decides. */
    @Test
    void withoutASecretsDirectory_theYamlDefaultAndTheVariablesStillApply() {
        assertThat(datasourcePassword(variables())).isEqualTo("picsou");
        assertThat(datasourcePassword(variables("POSTGRES_PASSWORD", "from-compose"))).isEqualTo("from-compose");
        assertThat(datasourcePassword(variables(
            "POSTGRES_PASSWORD", "from-compose", "SPRING_DATASOURCE_PASSWORD", "spring-override")))
            .isEqualTo("spring-override");
        assertThat(secretsDir()).doesNotExist();
    }

    // ─── consumers accept the generated values ────────────────────────────

    @Test
    void generatedValues_satisfyJwtUtilAndCryptoEncryption() {
        MockEnvironment environment = run();

        JwtUtil jwt = new JwtUtil(environment.getProperty("JWT_SECRET"), 15, 7, 5);
        CryptoEncryption crypto = new CryptoEncryption(environment.getProperty("CRYPTO_ENCRYPTION_KEY"));

        assertThat(jwt).isNotNull();
        assertThat(crypto.decrypt(crypto.encrypt("round trip"))).isEqualTo("round trip");
    }

    @Test
    void placeholdersResolveThroughTheGeneratedSource() {
        MockEnvironment environment = new MockEnvironment()
            .withProperty("PICSOU_SECRETS_DIR", secretsDir().toString())
            .withProperty("app.jwt.secret", "${JWT_SECRET}")
            .withProperty("app.crypto.encryption-key", "${CRYPTO_ENCRYPTION_KEY:}");

        run(environment);

        assertThat(environment.getProperty("app.jwt.secret")).isEqualTo(environment.getProperty("JWT_SECRET"));
        assertThat(environment.getProperty("app.crypto.encryption-key"))
            .isEqualTo(environment.getProperty("CRYPTO_ENCRYPTION_KEY"));
    }

    // ─── registration ─────────────────────────────────────────────────────

    @Test
    void isRegisteredWithSpringBoot() throws IOException {
        try (var context = new SpringApplicationBuilder(Empty.class)
            .web(WebApplicationType.NONE)
            .properties("PICSOU_SECRETS_DIR=" + secretsDir())
            .run()) {
            assertThat(context.getEnvironment().getProperty("JWT_SECRET")).isEqualTo(read("jwt_secret"));
        }
    }

    @org.springframework.context.annotation.Configuration
    static class Empty {}

    // ─── concurrent creator ───────────────────────────────────────────────

    @Test
    void writeIfAbsent_keepsAFileThatAppearedFirst() throws IOException {
        seed("jwt_secret", "created-by-another-process");

        boolean written = bootstrap.writeIfAbsent(secretsDir().resolve("jwt_secret"), "ours");

        assertThat(written).isFalse();
        assertThat(read("jwt_secret")).isEqualTo("created-by-another-process");
        try (var entries = Files.list(secretsDir())) {
            assertThat(entries.count()).isEqualTo(1);
        }
    }

    @Test
    void writeIfAbsent_createsAMissingFile() throws IOException {
        Files.createDirectories(secretsDir());

        boolean written = bootstrap.writeIfAbsent(secretsDir().resolve("jwt_secret"), "ours");

        assertThat(written).isTrue();
        assertThat(Files.readAllBytes(secretsDir().resolve("jwt_secret"))).isEqualTo("ours".getBytes(StandardCharsets.UTF_8));
    }

    // ─── dangling symlink ─────────────────────────────────────────────────
    //
    // Files.exists follows links, so a link to a missing target looks absent and generation runs.
    // The hard link then finds the name taken: the link must stay as it is and never be followed.

    private Path danglingTarget() {
        return root.resolve("planted-target");
    }

    private Path seedDanglingSymlink(String file) throws IOException {
        Files.createDirectories(secretsDir());
        return Files.createSymbolicLink(secretsDir().resolve(file), danglingTarget());
    }

    private void assertDanglingSymlinkIsUntouched(Path link) throws IOException {
        assertThat(link).isSymbolicLink();
        assertThat(Files.readSymbolicLink(link)).isEqualTo(danglingTarget());
        assertThat(danglingTarget()).doesNotExist();
    }

    @Test
    void writeIfAbsent_leavesADanglingSymlinkAlone() throws IOException {
        Path link = seedDanglingSymlink("jwt_secret");

        boolean written = bootstrap.writeIfAbsent(link, "ours");

        assertThat(written).isFalse();
        assertDanglingSymlinkIsUntouched(link);
        assertThat(secretsDirEntries()).containsExactly("jwt_secret");
    }

    @Test
    void danglingSymlink_refusesToStart_namingTheFile_withoutFollowingIt() throws IOException {
        Path link = seedDanglingSymlink("jwt_secret");

        assertThatThrownBy(() -> run())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining(link.toString())
            .hasMessageContaining("JWT_SECRET");
        assertDanglingSymlinkIsUntouched(link);
        assertThat(secretsDirEntries()).containsExactly("jwt_secret");
    }

    // ─── filesystem without hard links ────────────────────────────────────

    @Test
    void createExclusively_neverReplacesAFileThatAppearedFirst() throws IOException {
        seed("jwt_secret", "created-by-another-process");

        boolean created = bootstrap.createExclusively(secretsDir().resolve("jwt_secret"), "ours");

        assertThat(created).isFalse();
        assertThat(read("jwt_secret")).isEqualTo("created-by-another-process");
        try (var entries = Files.list(secretsDir())) {
            assertThat(entries.count()).isEqualTo(1);
        }
    }

    @Test
    void createExclusively_writesTheValueWithOwnerOnlyMode() throws IOException {
        Files.createDirectories(secretsDir());
        Path file = secretsDir().resolve("jwt_secret");

        // Even when the later chmod cannot run, the file is created owner-only.
        SecretBootstrap chmodFails = new SecretBootstrap(supplier -> supplier.get()) {
            @Override
            void setPermissions(Path path, String permissions) throws IOException {
                throw new IOException("chmod refused");
            }
        };

        boolean created = chmodFails.createExclusively(file, "ours \u00e9");

        assertThat(created).isTrue();
        assertThat(Files.readAllBytes(file)).isEqualTo("ours \u00e9".getBytes(StandardCharsets.UTF_8));
        assertThat(Files.getPosixFilePermissions(file)).isEqualTo(PosixFilePermissions.fromString("rw-------"));
    }

    static List<Exception> hardLinkFailures() {
        return List.of(new UnsupportedOperationException("no hard links"),
            new FileSystemException("jwt_secret", ".secret-x.tmp", "Operation not permitted"));
    }

    @ParameterizedTest
    @MethodSource("hardLinkFailures")
    void writeIfAbsent_withoutHardLinks_createsTheFileExclusively(Exception failure) throws IOException {
        SecretBootstrap withoutHardLinks = new SecretBootstrap(supplier -> supplier.get()) {
            @Override
            void link(Path file, Path temp) throws IOException {
                if (failure instanceof IOException io) {
                    throw io;
                }
                throw (RuntimeException) failure;
            }
        };
        Files.createDirectories(secretsDir());
        Path file = secretsDir().resolve("jwt_secret");

        boolean written = withoutHardLinks.writeIfAbsent(file, "ours \u00e9");

        assertThat(written).isTrue();
        assertThat(Files.readAllBytes(file)).isEqualTo("ours \u00e9".getBytes(StandardCharsets.UTF_8));
        assertThat(Files.getPosixFilePermissions(file)).isEqualTo(PosixFilePermissions.fromString("rw-------"));
        assertThat(secretsDirEntries()).containsExactly("jwt_secret");
    }

    @Test
    void createExclusively_failsWithoutLeavingAFileWhenTheDirectoryIsMissing() {
        Path file = secretsDir().resolve("jwt_secret");

        assertThatThrownBy(() -> bootstrap.createExclusively(file, "ours")).isInstanceOf(IOException.class);
        assertThat(file).doesNotExist();
    }

    @Test
    void secretsDirectoryIsCreatedWithItsParents() {
        run();

        assertThat(secretsDir()).isDirectory();
        assertThat(Set.of(secretsDir().toFile().list())).containsAll(FILES);
    }

    // ─── filesystem without POSIX modes ───────────────────────────────────

    @Test
    void suppliedSecretEndingWithANewline_isUsedAsIs_withAWarning() {
        List<String> warnings = new ArrayList<>();
        SecretBootstrap logged = new SecretBootstrap(supplier -> new NoOpLog() {
            @Override
            public void warn(Object message) {
                warnings.add(String.valueOf(message));
            }
        });
        MockEnvironment environment = new MockEnvironment()
            .withProperty("PICSOU_SECRETS_DIR", secretsDir().toString())
            .withProperty("JWT_SECRET", "supplied-jwt\n");

        logged.postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty("JWT_SECRET")).isEqualTo("supplied-jwt\n");
        assertThat(warnings).singleElement().asString()
            .contains("JWT_SECRET ends with a newline").doesNotContain("supplied-jwt");
    }

    /** NAS mounts without POSIX modes throw UnsupportedOperationException, not an IOException. */
    @Test
    void chmodUnsupported_logsAWarning_andDoesNotThrow() throws IOException {
        List<String> warnings = new ArrayList<>();
        SecretBootstrap noPosix = new SecretBootstrap(supplier -> new NoOpLog() {
            @Override
            public void warn(Object message) {
                warnings.add(String.valueOf(message));
            }
        }) {
            @Override
            void setPermissions(Path path, String permissions) {
                throw new UnsupportedOperationException("no POSIX modes");
            }
        };
        Files.createDirectories(secretsDir());

        noPosix.tryChmod(secretsDir(), "rwx------");

        assertThat(warnings).singleElement().asString().contains("could not chmod rwx------");
    }

    @Test
    void chmodFailure_logsAWarning_andDoesNotThrow() {
        List<String> warnings = new ArrayList<>();
        NoOpLog log = new NoOpLog() {
            @Override
            public void warn(Object message) {
                warnings.add(String.valueOf(message));
            }
        };
        SecretBootstrap logged = new SecretBootstrap(supplier -> log);
        Path missing = secretsDir().resolve("jwt_secret");

        logged.tryChmod(missing, "rw-------");

        assertThat(warnings).singleElement().asString()
            .contains("could not chmod rw-------")
            .contains(missing.toString());
    }
}
