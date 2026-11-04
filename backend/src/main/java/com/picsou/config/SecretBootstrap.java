package com.picsou.config;

import org.apache.commons.logging.Log;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.StandardEnvironment;
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
 * <p>{@code POSTGRES_PASSWORD} is never generated: the app cannot set the database's password,
 * so a generated one could only mismatch it. Compose and the Helm chart always supply it, and a
 * file an earlier version wrote is still read. With neither, nothing is published and
 * {@code application.yml} decides, with {@code picsou} as its default.
 *
 * <p>The datasource password is not set here: {@code application.yml} reads
 * {@code SPRING_DATASOURCE_PASSWORD}, then {@code POSTGRES_PASSWORD}, so the resolved password
 * reaches the datasource through Spring's ordinary precedence and every explicit
 * {@code spring.datasource.password} (command line, {@code -D}, {@code SPRING_APPLICATION_JSON},
 * the environment, a config file) outranks it. An empty {@code SPRING_DATASOURCE_PASSWORD} or
 * {@code POSTGRES_PASSWORD}, which Compose-style files produce for an unset variable, is treated
 * as unset, with or without a secrets directory.
 */
public class SecretBootstrap implements EnvironmentPostProcessor, Ordered {

    static final String SECRETS_DIR_PROPERTY = "picsou.secrets-dir";
    static final String SECRETS_DIR_ENV = "PICSOU_SECRETS_DIR";
    static final String SOURCE_NAME = "picsouGeneratedSecrets";
    static final String DATASOURCE_PASSWORD_ENV = "SPRING_DATASOURCE_PASSWORD";
    static final String POSTGRES_PASSWORD = "POSTGRES_PASSWORD";
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
        new Secret(POSTGRES_PASSWORD, "postgres_password", NEVER_GENERATED));

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
        dropEmptyPasswordVariables(environment);
        if (!StringUtils.hasText(configured)) {
            return;
        }
        Path dir = Path.of(configured);
        prepareDirectory(dir);

        Map<String, Object> resolved = new LinkedHashMap<>();
        for (Secret secret : SECRETS) {
            resolve(environment, dir, secret).ifPresent(value -> resolved.put(secret.variable(), value));
        }
        environment.getPropertySources().addFirst(new MapPropertySource(SOURCE_NAME, resolved));
    }

    /**
     * Compose-style files turn an unset variable into an empty one, while application.yml's
     * {@code ${SPRING_DATASOURCE_PASSWORD:${POSTGRES_PASSWORD:picsou}}} and Spring's relaxed binding
     * only treat an absent one as unset. An empty {@code SPRING_DATASOURCE_PASSWORD} or
     * {@code POSTGRES_PASSWORD} is removed from the environment source, so the datasource gets
     * exactly what an unset variable gives: an explicit {@code spring.datasource.password} from any
     * source, a config file included, else the resolved {@code POSTGRES_PASSWORD}, else
     * {@code picsou}. Runs with or without a secrets directory.
     */
    private static void dropEmptyPasswordVariables(ConfigurableEnvironment environment) {
        MutablePropertySources sources = environment.getPropertySources();
        String name = StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME;
        if (!(sources.get(name) instanceof MapPropertySource system)) {
            return;
        }
        Map<String, Object> variables = new LinkedHashMap<>(system.getSource());
        boolean dropped = variables.entrySet().removeIf(entry ->
            (DATASOURCE_PASSWORD_ENV.equals(entry.getKey()) || POSTGRES_PASSWORD.equals(entry.getKey()))
                && "".equals(entry.getValue()));
        if (dropped) {
            sources.replace(name, new SystemEnvironmentPropertySource(name, variables));
        }
    }

    /** The secret's value, or empty for a secret that is never generated and was not found. */
    private Optional<String> resolve(ConfigurableEnvironment environment, Path dir, Secret secret) {
        Path file = dir.resolve(secret.fileName());
        String supplied = environment.getProperty(secret.variable());
        if (StringUtils.hasLength(supplied)) {
            if (supplied.endsWith("\n") && secret.isGenerated()) {
                // Used verbatim while the variable is set, but read back without the newline once it
                // is removed: the key would change then. Not stripped here, which would change it now.
                log.warn(secret.variable() + " ends with a newline. It is used as is, but the copy in "
                    + file + " is read without it if the variable is removed later. Remove the newline.");
            }
            writeIfAbsent(file, supplied);
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
        return Optional.of(read(file, secret));
    }

    private void prepareDirectory(Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot create secrets directory " + dir, ex);
        }
        tryChmod(dir, "rwx------");
    }

    private String read(Path file, Secret secret) {
        String content;
        try {
            content = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException(
                "Cannot read secret file " + file + " for " + secret.variable(), ex);
        }
        // Files written by the former shell entrypoint end with a newline that `$(cat file)` dropped.
        int end = content.length();
        while (end > 0 && content.charAt(end - 1) == '\n') {
            end--;
        }
        if (end == 0) {
            throw new IllegalStateException(
                "Secret file " + file + " is empty: refusing to start with a blank " + secret.variable());
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
