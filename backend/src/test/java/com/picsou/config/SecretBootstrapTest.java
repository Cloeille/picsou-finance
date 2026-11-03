package com.picsou.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Base64;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecretBootstrapTest {

    private static final List<String> FILES = List.of("jwt_secret", "crypto_key", "postgres_password");

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

    // ─── generation ───────────────────────────────────────────────────────

    @Test
    void firstStart_generatesThreeSecretsWithExpectedSizesAndModes() throws IOException {
        MockEnvironment environment = run();

        assertThat(decodedLength(environment.getProperty("JWT_SECRET"))).isEqualTo(48);
        assertThat(decodedLength(environment.getProperty("CRYPTO_ENCRYPTION_KEY"))).isEqualTo(32);
        assertThat(decodedLength(environment.getProperty("POSTGRES_PASSWORD"))).isEqualTo(24);
        for (String file : FILES) {
            assertThat(Files.getPosixFilePermissions(secretsDir().resolve(file)))
                .isEqualTo(PosixFilePermissions.fromString("rw-------"));
        }
        assertThat(Files.getPosixFilePermissions(secretsDir()))
            .isEqualTo(PosixFilePermissions.fromString("rwx------"));
        assertThat(environment.getProperty("JWT_SECRET")).isEqualTo(read("jwt_secret"));
        assertThat(environment.getProperty("CRYPTO_ENCRYPTION_KEY")).isEqualTo(read("crypto_key"));
        assertThat(environment.getProperty("POSTGRES_PASSWORD")).isEqualTo(read("postgres_password"));
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
        byte[] postgres = Files.readAllBytes(secretsDir().resolve("postgres_password"));

        MockEnvironment second = run();

        assertThat(Files.readAllBytes(secretsDir().resolve("jwt_secret"))).isEqualTo(jwt);
        assertThat(Files.readAllBytes(secretsDir().resolve("crypto_key"))).isEqualTo(crypto);
        assertThat(Files.readAllBytes(secretsDir().resolve("postgres_password"))).isEqualTo(postgres);
        assertThat(second.getProperty("JWT_SECRET")).isEqualTo(first.getProperty("JWT_SECRET"));
        assertThat(second.getProperty("CRYPTO_ENCRYPTION_KEY")).isEqualTo(first.getProperty("CRYPTO_ENCRYPTION_KEY"));
        assertThat(second.getProperty("POSTGRES_PASSWORD")).isEqualTo(first.getProperty("POSTGRES_PASSWORD"));
    }

    @Test
    void existingFile_isUsedWhenVariableIsUnset() throws IOException {
        seed("postgres_password", "fixture-postgres-password");

        MockEnvironment environment = run();

        assertThat(environment.getProperty("POSTGRES_PASSWORD")).isEqualTo("fixture-postgres-password");
        assertThat(environment.getProperty("spring.datasource.password")).isEqualTo("fixture-postgres-password");
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
        String postgres = read("postgres_password");
        Files.writeString(secretsDir().resolve("crypto_key"), "");

        assertThatThrownBy(() -> run())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining(secretsDir().resolve("crypto_key").toString())
            .hasMessageContaining("CRYPTO_ENCRYPTION_KEY")
            .satisfies(ex -> assertThat(ex.getMessage())
                .doesNotContain(jwt).doesNotContain(crypto).doesNotContain(postgres));
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

    @Test
    void generatedPostgresPassword_isUsedByTheDatasource() throws IOException {
        MockEnvironment environment = run();

        assertThat(environment.getProperty("spring.datasource.password"))
            .isEqualTo(environment.getProperty("POSTGRES_PASSWORD"))
            .isEqualTo(read("postgres_password"));
    }

    @Test
    void postgresPassword_isUsedWhenNoSpringOverrideExists() {
        String password = "p; $pecial'\"\\pass";

        MockEnvironment environment = run("POSTGRES_PASSWORD", password);

        assertThat(environment.getProperty("spring.datasource.password")).isEqualTo(password);
    }

    @Test
    void nonEmptySpringPasswordOverride_isPreserved() {
        MockEnvironment environment = run(
            "POSTGRES_PASSWORD", "postgres-special; $value",
            "SPRING_DATASOURCE_PASSWORD", "spring-override");

        assertThat(environment.getProperty("spring.datasource.password")).isEqualTo("spring-override");
        assertThat(environment.getProperty("POSTGRES_PASSWORD")).isEqualTo("postgres-special; $value");
    }

    @Test
    void emptySpringPasswordOverride_fallsBackToPostgresPassword() {
        MockEnvironment environment = run(
            "POSTGRES_PASSWORD", "postgres-special; $value",
            "SPRING_DATASOURCE_PASSWORD", "");

        assertThat(environment.getProperty("spring.datasource.password")).isEqualTo("postgres-special; $value");
    }

    @Test
    void explicitPicsouPassword_isPreserved() {
        assertThat(run("POSTGRES_PASSWORD", "picsou").getProperty("spring.datasource.password")).isEqualTo("picsou");
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
    void isRegisteredWithSpringBoot() {
        try (var context = new org.springframework.boot.builder.SpringApplicationBuilder(Empty.class)
            .web(org.springframework.boot.WebApplicationType.NONE)
            .properties("PICSOU_SECRETS_DIR=" + secretsDir())
            .run()) {
            assertThat(context.getEnvironment().getProperty("spring.datasource.password"))
                .isEqualTo(context.getEnvironment().getProperty("POSTGRES_PASSWORD"))
                .isNotBlank();
        }
        assertThat(secretsDir().resolve("jwt_secret")).exists();
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

        boolean created = bootstrap.createExclusively(file, "ours \u00e9");

        assertThat(created).isTrue();
        assertThat(Files.readAllBytes(file)).isEqualTo("ours \u00e9".getBytes(StandardCharsets.UTF_8));
        assertThat(Files.getPosixFilePermissions(file)).isEqualTo(PosixFilePermissions.fromString("rw-------"));
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
}
