package com.picsou.config;

import org.apache.commons.logging.Log;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Resolves the install secrets before any bean exists, so {@link JwtUtil},
 * {@link CryptoEncryption} and the datasource keep reading plain properties.
 *
 * <p>Active only when {@code PICSOU_SECRETS_DIR} (property {@code picsou.secrets-dir}) is set.
 * For each secret an explicit value wins (and is persisted when its file is missing), then the
 * file in that directory. {@code JWT_SECRET} and {@code CRYPTO_ENCRYPTION_KEY} are otherwise
 * generated and persisted there. A secret is never regenerated once its file exists: doing so
 * would log every user out and make every stored API secret undecryptable.
 *
 * <p>{@code POSTGRES_PASSWORD} is never generated and never written: the app cannot set the
 * database's password, so a generated or stale one could only mismatch it. Compose and the Helm
 * chart always supply it, and a file an earlier version wrote is still read.
 *
 * <p>Secret values are read <em>raw</em>, straight from the property sources: a password holding
 * {@code ${...}} keeps its characters instead of being expanded as a placeholder (which would
 * throw, or write the expanded value to the log).
 *
 * <p>An empty {@code SPRING_DATASOURCE_PASSWORD} or {@code POSTGRES_PASSWORD} is what a
 * Compose-style file gives for an unset variable, while Spring's relaxed binding and
 * {@code application.yml}'s template only treat an absent one as unset. When one of the two is
 * present but empty, both are published with the fallback password: an explicit
 * {@code spring.datasource.password} from the first source holding a literal one, then a non-empty
 * {@code POSTGRES_PASSWORD}, then the {@code postgres_password} file an earlier version wrote,
 * then {@code picsou}. Every other start keeps Spring's ordinary precedence, untouched.
 */
public class SecretBootstrap implements EnvironmentPostProcessor, Ordered {

    static final String SECRETS_DIR_PROPERTY = "picsou.secrets-dir";
    static final String SECRETS_DIR_ENV = "PICSOU_SECRETS_DIR";
    static final String SOURCE_NAME = "picsouGeneratedSecrets";
    static final String DATASOURCE_PASSWORD_ENV = "SPRING_DATASOURCE_PASSWORD";
    static final String POSTGRES_PASSWORD = "POSTGRES_PASSWORD";
    static final String POSTGRES_PASSWORD_FILE = "postgres_password";
    static final String DATASOURCE_PASSWORD_PROPERTY = "spring.datasource.password";
    /** The last fallback of {@code spring.datasource.password} in {@code application.yml}. */
    static final String DEFAULT_DATASOURCE_PASSWORD = "picsou";

    private static final SecureRandom RANDOM = new SecureRandom();

    private static final int NEVER_GENERATED = 0;

    /** {@code generatedBytes} is the size of a generated value, or {@link #NEVER_GENERATED}. */
    private record Secret(String variable, String fileName, int generatedBytes) {
        boolean isGenerated() {
            return generatedBytes != NEVER_GENERATED;
        }
    }

    private static final List<Secret> SECRETS = List.of(
        new Secret("JWT_SECRET", "jwt_secret", 48),
        new Secret("CRYPTO_ENCRYPTION_KEY", "crypto_key", 32),
        new Secret(POSTGRES_PASSWORD, POSTGRES_PASSWORD_FILE, NEVER_GENERATED));

    private final Log log;

    public SecretBootstrap(DeferredLogFactory logFactory) {
        this.log = logFactory.getLog(SecretBootstrap.class);
    }

    @Override
    public int getOrder() {
        // After config data, so picsou.secrets-dir may come from application.yml.
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String configured = environment.getProperty(SECRETS_DIR_PROPERTY);
        if (!StringUtils.hasText(configured)) {
            configured = environment.getProperty(SECRETS_DIR_ENV);
        }
        Path dir = StringUtils.hasText(configured) ? Path.of(configured) : null;
        Map<String, Object> resolved = new LinkedHashMap<>();
        if (hasAnEmptyPasswordVariable(environment)) {
            // application.yml reads SPRING_DATASOURCE_PASSWORD, then POSTGRES_PASSWORD. Publishing
            // both names, rather than spring.datasource.password itself, keeps that template in
            // charge: a value substituted into it is not scanned for placeholders again, so a
            // password holding ${...} survives, while a password published as the template's own
            // value would be expanded.
            String fallback = fallbackDatasourcePassword(environment, dir);
            resolved.put(DATASOURCE_PASSWORD_ENV, fallback);
            resolved.put(POSTGRES_PASSWORD, fallback);
        }
        if (dir != null) {
            prepareDirectory(dir);
            for (Secret secret : SECRETS) {
                resolve(environment, dir, secret).ifPresent(value -> resolved.put(secret.variable(), value));
            }
        }
        if (!resolved.isEmpty()) {
            // An environment-style source, not a plain MapPropertySource: Boot's relaxed binding
            // only maps SPRING_DATASOURCE_PASSWORD to spring.datasource.password for a source it
            // knows as an environment, and the fallback above is published under those names.
            environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource(SOURCE_NAME, resolved));
        }
    }

    /**
     * {@code SPRING_DATASOURCE_PASSWORD} or {@code POSTGRES_PASSWORD} is present with an empty value,
     * which Compose-style files give for a variable the user never set. Spring's relaxed binding
     * cannot tell that apart from an unset one, and neither can {@code application.yml}, so the
     * datasource password is published explicitly in that case, and in that case only.
     */
    private static boolean hasAnEmptyPasswordVariable(ConfigurableEnvironment environment) {
        return "".equals(valueOf(environment, DATASOURCE_PASSWORD_ENV))
            || "".equals(valueOf(environment, POSTGRES_PASSWORD));
    }

    /**
     * What the datasource should get when a password variable is present but empty: an explicit
     * {@code spring.datasource.password} from the first source that holds a literal one, then a
     * non-empty {@code POSTGRES_PASSWORD}, then the {@code postgres_password} file an earlier
     * version wrote, then {@code picsou}. An empty value counts as unset, at every step.
     */
    private static String fallbackDatasourcePassword(ConfigurableEnvironment environment, Path dir) {
        String explicit = explicitDatasourcePassword(environment);
        if (explicit != null) {
            return explicit;
        }
        String postgres = firstNonEmpty(environment, POSTGRES_PASSWORD);
        if (postgres != null) {
            return postgres;
        }
        if (dir != null) {
            Path legacy = dir.resolve(POSTGRES_PASSWORD_FILE);
            if (Files.exists(legacy)) {
                return read(legacy, POSTGRES_PASSWORD);
            }
        }
        return DEFAULT_DATASOURCE_PASSWORD;
    }

    /**
     * An explicit {@code spring.datasource.password}, or {@code null}. {@code application.yml}
     * itself holds {@code ${SPRING_DATASOURCE_PASSWORD:${POSTGRES_PASSWORD:picsou}}} under that
     * name, which is a template and not a password: any value holding a placeholder reference is
     * skipped, so the search reaches the source the user actually set.
     */
    private static String explicitDatasourcePassword(ConfigurableEnvironment environment) {
        for (PropertySource<?> source : environment.getPropertySources()) {
            Object value = source.getProperty(DATASOURCE_PASSWORD_PROPERTY);
            if (value == null) {
                continue;
            }
            String text = value.toString();
            if (!text.isEmpty() && !text.contains("${")) {
                return text;
            }
        }
        return null;
    }

    /** The first non-empty value of {@code name} among the property sources, or {@code null}. */
    private static String firstNonEmpty(ConfigurableEnvironment environment, String name) {
        String value = valueOf(environment, name);
        return StringUtils.hasLength(value) ? value : null;
    }

    /**
     * The raw value of {@code name}: {@code source.getProperty} directly, never
     * {@code environment.getProperty}, which expands placeholders. A secret is not a template, and
     * a database password holding {@code ${...}} must reach the datasource byte for byte. The
     * sources are walked in order, so an explicit {@code spring.datasource.password} keeps the
     * precedence Spring itself would give it.
     */
    private static String valueOf(ConfigurableEnvironment environment, String name) {
        for (PropertySource<?> source : environment.getPropertySources()) {
            Object value = source.getProperty(name);
            if (value != null) {
                return value.toString();
            }
        }
        return null;
    }

    /** The secret's value, or empty for a secret that is never generated and was not found. */
    private Optional<String> resolve(ConfigurableEnvironment environment, Path dir, Secret secret) {
        Path file = dir.resolve(secret.fileName());
        String supplied = valueOf(environment, secret.variable());
        if (StringUtils.hasLength(supplied)) {
            if (supplied.endsWith("\n") && secret.isGenerated()) {
                // Used verbatim while the variable is set, but read back without the newline once it
                // is removed: the key would change then. Not stripped here, which would change it now.
                log.warn(secret.variable() + " ends with a newline. It is used as is, but the copy in "
                    + file + " is read without it if the variable is removed later. Remove the newline.");
            }
            if (secret.isGenerated()) {
                // Only a secret the app owns is persisted. Writing POSTGRES_PASSWORD here would let a
                // wrong variable keep overriding the database's own password afterwards.
                writeIfAbsent(file, supplied);
            }
            return Optional.of(supplied);
        }
        if (!Files.exists(file)) {
            if (!secret.isGenerated()) {
                return Optional.empty();
            }
            String generated = generate(secret.generatedBytes());
            if (writeIfAbsent(file, generated)) {
                log.info("generated " + secret.variable() + " at " + file);
                return Optional.of(generated);
            }
            // Another process created the file first: use its content.
        }
        return Optional.of(read(file, secret.variable()));
    }

    private void prepareDirectory(Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot create secrets directory " + dir, ex);
        }
        tryChmod(dir, "rwx------");
    }

    private static String read(Path file, String variable) {
        String content;
        try {
            content = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException(
                "Cannot read secret file " + file + " for " + variable, ex);
        }
        // Files written by the former shell entrypoint end with a newline that `$(cat file)` dropped.
        int end = content.length();
        while (end > 0 && content.charAt(end - 1) == '\n') {
            end--;
        }
        if (end == 0) {
            throw new IllegalStateException(
                "Secret file " + file + " is empty: refusing to start with a blank " + variable);
        }
        return content.substring(0, end);
    }

    /**
     * Writes {@code value} to {@code file} unless it already exists, and never replaces a file.
     * Returns {@code false} when the file was already there.
     *
     * <p>The value is written to a temp file and hard-linked into place, so the final name never
     * shows a partial write. A filesystem without hard links (some NAS mounts) falls back to
     * {@link #createExclusively}, where the final file is visible while its bytes are still landing.
     */
    boolean writeIfAbsent(Path file, String value) {
        if (Files.exists(file)) {
            return false;
        }
        Path temp = null;
        try {
            temp = Files.createTempFile(file.getParent(), ".secret-", ".tmp");
            Files.writeString(temp, value, StandardCharsets.UTF_8);
            tryChmod(temp, "rw-------");
            try {
                // A hard link fails when the target exists, unlike a rename, which would replace it.
                link(file, temp);
            } catch (FileAlreadyExistsException ex) {
                return false;
            } catch (UnsupportedOperationException | IOException ex) {
                return createExclusively(file, value);
            }
            return true;
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot write secret file " + file, ex);
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // Best effort: the temp file holds nothing the final file does not.
                }
            }
        }
    }

    /** Hard-links {@code temp} as {@code file}; overridable so tests can simulate a filesystem without hard links. */
    void link(Path file, Path temp) throws IOException {
        Files.createLink(file, temp);
    }

    /**
     * Creates {@code file} with {@code CREATE_NEW} ({@code O_EXCL}), which fails atomically when
     * it exists, then writes and syncs the value. Unlike a rename it can not replace a file another
     * process created in the meantime. A reader that opens the file before the bytes land finds it
     * empty, and {@link #read} refuses to start on an empty file, so that window fails closed.
     * The mode is restricted before the value is written, and a failed write removes the file this
     * call created, so no truncated secret is left behind to be reused.
     */
    boolean createExclusively(Path file, String value) throws IOException {
        FileChannel channel;
        try {
            channel = openOwnerOnly(file);
        } catch (FileAlreadyExistsException ex) {
            return false;
        }
        try (channel) {
            tryChmod(file, "rw-------");
            ByteBuffer bytes = ByteBuffer.wrap(value.getBytes(StandardCharsets.UTF_8));
            while (bytes.hasRemaining()) {
                channel.write(bytes);
            }
            channel.force(true);
        } catch (IOException ex) {
            Files.deleteIfExists(file);
            throw ex;
        }
        return true;
    }

    /** {@code CREATE_NEW} with mode {@code 0600} from creation; without it on a filesystem that has no POSIX modes. */
    private static FileChannel openOwnerOnly(Path file) throws IOException {
        try {
            return FileChannel.open(file, Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        } catch (UnsupportedOperationException noPosixModes) {
            return FileChannel.open(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        }
    }

    void tryChmod(Path path, String permissions) {
        try {
            setPermissions(path, permissions);
        } catch (UnsupportedOperationException | IOException ex) {
            // NAS bind mounts (SMB/CIFS, some FUSE) cannot represent POSIX modes: warn, do not fail.
            log.warn("could not chmod " + permissions + " " + path
                + ": the filesystem may not support it. Secrets may be readable by other users on the host.");
        }
    }

    /** Overridable so tests can simulate a filesystem without POSIX modes. */
    void setPermissions(Path path, String permissions) throws IOException {
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(permissions));
    }

    private static String generate(int bytes) {
        byte[] random = new byte[bytes];
        RANDOM.nextBytes(random);
        return Base64.getEncoder().encodeToString(random);
    }
}
