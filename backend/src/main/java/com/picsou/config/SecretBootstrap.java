package com.picsou.config;

import org.apache.commons.logging.Log;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves the three install secrets before any bean exists, so {@link JwtUtil},
 * {@link CryptoEncryption} and the datasource keep reading plain properties.
 *
 * <p>Active only when {@code PICSOU_SECRETS_DIR} (property {@code picsou.secrets-dir}) is set.
 * For each secret an explicit value wins, then the file in that directory, then a freshly
 * generated value that is persisted there. A secret is never regenerated once its file exists:
 * doing so would log every user out and make every stored API secret undecryptable.
 */
public class SecretBootstrap implements EnvironmentPostProcessor, Ordered {

    static final String SECRETS_DIR_PROPERTY = "picsou.secrets-dir";
    static final String SECRETS_DIR_ENV = "PICSOU_SECRETS_DIR";
    static final String SOURCE_NAME = "picsouGeneratedSecrets";
    static final String DATASOURCE_PASSWORD_ENV = "SPRING_DATASOURCE_PASSWORD";
    static final String DATASOURCE_PASSWORD_PROPERTY = "spring.datasource.password";
    static final String POSTGRES_PASSWORD = "POSTGRES_PASSWORD";

    private static final SecureRandom RANDOM = new SecureRandom();

    private record Secret(String variable, String fileName, int bytes) {}

    private static final List<Secret> SECRETS = List.of(
        new Secret("JWT_SECRET", "jwt_secret", 48),
        new Secret("CRYPTO_ENCRYPTION_KEY", "crypto_key", 32),
        new Secret(POSTGRES_PASSWORD, "postgres_password", 24));

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
        if (!StringUtils.hasText(configured)) {
            return;
        }
        Path dir = Path.of(configured);
        prepareDirectory(dir);

        Map<String, Object> resolved = new LinkedHashMap<>();
        for (Secret secret : SECRETS) {
            resolved.put(secret.variable(), resolve(environment, dir, secret));
        }
        String datasourcePassword = environment.getProperty(DATASOURCE_PASSWORD_ENV);
        resolved.put(DATASOURCE_PASSWORD_PROPERTY,
            StringUtils.hasLength(datasourcePassword) ? datasourcePassword : resolved.get(POSTGRES_PASSWORD));
        environment.getPropertySources().addFirst(new MapPropertySource(SOURCE_NAME, resolved));
    }

    private String resolve(ConfigurableEnvironment environment, Path dir, Secret secret) {
        Path file = dir.resolve(secret.fileName());
        String supplied = environment.getProperty(secret.variable());
        if (StringUtils.hasLength(supplied)) {
            writeIfAbsent(file, supplied);
            return supplied;
        }
        if (!Files.exists(file)) {
            String generated = generate(secret.bytes());
            if (writeIfAbsent(file, generated)) {
                log.info("generated " + secret.variable() + " at " + file);
                return generated;
            }
            // Another process created the file first: use its content.
        }
        return read(file, secret);
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
     * Writes {@code value} to {@code file} unless it already exists, without ever exposing a
     * partially written file. Returns {@code false} when the file was already there.
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
                Files.createLink(file, temp);
            } catch (FileAlreadyExistsException ex) {
                return false;
            } catch (UnsupportedOperationException | IOException ex) {
                // Hard links unavailable (some NAS filesystems): fall back to a rename.
                if (Files.exists(file)) {
                    return false;
                }
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE);
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

    private void tryChmod(Path path, String permissions) {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(permissions));
        } catch (UnsupportedOperationException | IOException ex) {
            // NAS bind mounts (SMB/CIFS, some FUSE) cannot represent POSIX modes: warn, do not fail.
            log.warn("could not chmod " + permissions + " " + path
                + ": the filesystem may not support it. Secrets may be readable by other users on the host.");
        }
    }

    private static String generate(int bytes) {
        byte[] random = new byte[bytes];
        RANDOM.nextBytes(random);
        return Base64.getEncoder().encodeToString(random);
    }
}
