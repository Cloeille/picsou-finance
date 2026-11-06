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
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Resolves the install secrets before any bean exists, so {@link JwtUtil},
 * {@link CryptoEncryption} and the datasource keep reading plain properties.
 *
 * <p>Active only when {@code PICSOU_SECRETS_DIR} (property {@code picsou.secrets-dir}) is set.
 * For {@code JWT_SECRET} and {@code CRYPTO_ENCRYPTION_KEY} an explicit value wins (and is
 * persisted when its file is missing), then the file in that directory; otherwise a value is
 * generated and persisted there. A secret is never regenerated once its file exists: doing so
 * would log every user out and make every stored API secret undecryptable.
 *
 * <p>{@code POSTGRES_PASSWORD} is never generated and never written: the app cannot set the
 * database's password, so a generated or stale one could only mismatch it. Compose and the Helm
 * chart always supply it, and a file an earlier version wrote is still read.
 *
 * <p>Secret values are read <em>raw</em>, straight from the property sources, so this processor
 * never expands a {@code ${...}} itself (which would throw, or write the expanded value to the
 * log). Consumers still read them through Spring: a {@code JWT_SECRET} holding an unresolvable
 * {@code ${...}} fails at {@code ${app.jwt.secret}}, as it did before this class existed.
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
    /** {@code spring.datasource.password} as the bundled {@code application.yml} declares it. */
    static final String DATASOURCE_PASSWORD_TEMPLATE = "${SPRING_DATASOURCE_PASSWORD:${POSTGRES_PASSWORD:picsou}}";
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
            // application.yml reads SPRING_DATASOURCE_PASSWORD, then POSTGRES_PASSWORD. Both names
            // are published, rather than spring.datasource.password itself, so that template stays
            // in charge of the precedence. Spring still resolves placeholders inside the value it
            // substitutes: ${HOME} in a password is expanded, and an unresolvable ${nope} survives
            // only because the datasource binder ignores unresolvable placeholders.
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
     * An explicit {@code spring.datasource.password}, or {@code null}. The bundled
     * {@code application.yml} declares {@link #DATASOURCE_PASSWORD_TEMPLATE} under that name, which
     * is the precedence itself and not a password, so that exact value is skipped. Any other value
     * is the operator's, a {@code ${DB_PASSWORD}} reference included: it is returned as is and
     * Spring resolves it as it would without this processor.
     */
    private static String explicitDatasourcePassword(ConfigurableEnvironment environment) {
        for (PropertySource<?> source : environment.getPropertySources()) {
            Object value = source.getProperty(DATASOURCE_PASSWORD_PROPERTY);
            if (value != null && !value.toString().isEmpty()
                && !DATASOURCE_PASSWORD_TEMPLATE.equals(value.toString())) {
                return value.toString();
            }
        }
        return null;
    }

    /**
     * {@code SPRING_DATASOURCE_PASSWORD} or {@code spring.datasource.password} holds a password, so
     * the legacy {@code postgres_password} file is never consulted, nor refused when it is empty.
     */
    private static boolean hasAnExplicitDatasourcePassword(ConfigurableEnvironment environment) {
        return firstNonEmpty(environment, DATASOURCE_PASSWORD_ENV) != null
            || explicitDatasourcePassword(environment) != null;
    }

    /**
     * The first non-empty value of {@code name} among the property sources, or {@code null}. An
     * empty value in one source, as Compose gives for an unset variable, does not hide a real one
     * in a later source.
     */
    private static String firstNonEmpty(ConfigurableEnvironment environment, String name) {
        for (PropertySource<?> source : environment.getPropertySources()) {
            Object value = source.getProperty(name);
            if (value != null && !value.toString().isEmpty()) {
                return value.toString();
            }
        }
        return null;
    }

    /**
     * The raw value of {@code name}: {@code source.getProperty} directly, never
     * {@code environment.getProperty}, which expands placeholders and throws on an unresolvable
     * one, its message holding the whole value. This processor never expands a secret. The
     * datasource binder still does, as it would without this processor: an unresolvable
     * {@code ${nope}} reaches it unchanged, while a {@code ${HOME}} is expanded. The sources are
     * walked in order, so an explicit {@code spring.datasource.password} keeps the precedence
     * Spring itself would give it.
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
        if (!secret.isGenerated() && hasAnExplicitDatasourcePassword(environment)) {
            // The datasource has its password, and the legacy file would lose to it anyway.
            return Optional.empty();
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
     * <p>The value is written and synced to a temp file, then hard-linked into place, so the final
     * name never shows a partial write. A filesystem without hard links (some NAS mounts) falls back
     * to {@link #publishUnderLock}, which renames the complete temp file into place instead.
     */
    boolean writeIfAbsent(Path file, String value) {
        if (Files.exists(file)) {
            return false;
        }
        Path temp = null;
        try {
            temp = Files.createTempFile(file.getParent(), ".secret-", ".tmp");
            tryChmod(temp, "rw-------");
            writeAndSync(temp, value);
            try {
                // A hard link fails when the target exists, unlike a rename, which would replace it.
                link(file, temp);
            } catch (FileAlreadyExistsException ex) {
                return false;
            } catch (UnsupportedOperationException | IOException ex) {
                return publishUnderLock(file, temp);
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
     * Moves the complete {@code temp} file to {@code file} on a filesystem without hard links.
     * A rename replaces an existing target, so publishers take turns through {@code <file>.lock},
     * created with {@code CREATE_NEW} ({@code O_EXCL}): the holder checks the name is still free,
     * renames, then removes the lock. The final name only ever appears with its whole value, so a
     * concurrent reader can not take a prefix of a key for a shorter, valid one.
     *
     * <p>A start that finds the lock waits up to {@link #lockWait()} for the holder's file, then
     * uses it. A holder that releases the lock without a file (an I/O error) hands the turn to the
     * next start, which takes the lock and publishes its own value. A lock still held with no file
     * after that wait is left by a crashed start: the app refuses to start and names the lock to
     * delete, rather than guess whether another instance is writing.
     */
    boolean publishUnderLock(Path file, Path temp) throws IOException {
        Path lock = file.resolveSibling(file.getFileName() + ".lock");
        if (!acquireLock(file, lock)) {
            return false;
        }
        try {
            if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                return false;
            }
            try {
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ex) {
                // Same directory: still a rename, and the lock keeps other publishers out.
                Files.move(temp, file);
            }
            return true;
        } finally {
            Files.deleteIfExists(lock);
        }
    }

    /**
     * Takes {@code lock} and returns {@code true}, or returns {@code false} once the holder's
     * {@code file} exists. The lock is retried on every turn, so a holder that gave up without
     * a file is not blamed for a lock that is already gone.
     */
    private boolean acquireLock(Path file, Path lock) throws IOException {
        long deadline = System.nanoTime() + lockWait().toNanos();
        while (true) {
            try {
                Files.createFile(lock);
                return true;
            } catch (FileAlreadyExistsException held) {
                // Another start is publishing: wait for its file below.
            }
            if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                return false;
            }
            if (System.nanoTime() - deadline >= 0) {
                throw new IllegalStateException("Secret file " + file + " is still being created behind "
                    + lock + ". Delete " + lock + " if no other Picsou instance is starting.");
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for " + file, ex);
            }
        }
    }

    /** How long a start waits for another publisher's file; overridable so tests do not wait. */
    Duration lockWait() {
        return Duration.ofSeconds(10);
    }

    private static void writeAndSync(Path path, String value) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            ByteBuffer bytes = ByteBuffer.wrap(value.getBytes(StandardCharsets.UTF_8));
            while (bytes.hasRemaining()) {
                channel.write(bytes);
            }
            channel.force(true);
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
