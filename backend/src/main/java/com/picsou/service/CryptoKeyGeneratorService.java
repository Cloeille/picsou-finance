package com.picsou.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.EnumSet;
import java.util.Set;

/**
 * Idempotent AES-256 key writer for the wizard's Crypto step.
 *
 * <p>The common case is that {@link com.picsou.config.SecretBootstrap} has already
 * generated {@code crypto_key} in {@code PICSOU_SECRETS_DIR} before any bean existed (because
 * {@link com.picsou.config.CryptoEncryption} reads the key at bean
 * construction time — it must exist pre-boot). In that case this service
 * just reports {@code existed=true} and the wizard shows a "You're all set"
 * state.
 *
 * <p>For bare-metal installs where {@code PICSOU_SECRETS_DIR} is not set,
 * when {@code CRYPTO_ENCRYPTION_KEY} is already present in the runtime
 * environment, the setup step treats that as the source of truth and never
 * tries to write Docker's {@code /data} path. That keeps bare-metal dev
 * installs from failing on a read-only root while preserving the Docker file
 * fallback for environments that explicitly configure {@code app.crypto.key-path}.
 */
@Service
public class CryptoKeyGeneratorService {

    private static final Logger log = LoggerFactory.getLogger(CryptoKeyGeneratorService.class);
    private static final int AES_BITS = 256;
    private static final Set<PosixFilePermission> FILE_PERMS =
        PosixFilePermissions.fromString("rw-------");
    private static final Set<PosixFilePermission> DIR_PERMS =
        EnumSet.of(PosixFilePermission.OWNER_READ,
                   PosixFilePermission.OWNER_WRITE,
                   PosixFilePermission.OWNER_EXECUTE);

    private final Path keyPath;
    private final boolean runtimeKeyConfigured;

    public CryptoKeyGeneratorService(
        @Value("${app.crypto.key-path:/data/.secrets/crypto_key}") String path,
        @Value("${app.crypto.encryption-key:}") String runtimeKey
    ) {
        this.keyPath = Path.of(path);
        this.runtimeKeyConfigured = runtimeKey != null && !runtimeKey.isBlank();
    }

    public boolean exists() {
        return runtimeKeyConfigured || Files.exists(keyPath);
    }

    public Path keyPath() {
        return keyPath;
    }

    public String keyLocation() {
        return runtimeKeyConfigured ? "CRYPTO_ENCRYPTION_KEY" : keyPath.toString();
    }

    /**
     * Returns {@code true} if a new key was created, {@code false} if one
     * already existed. Never regenerates an existing key — that would
     * silently render every previously-encrypted secret undecryptable.
     */
    public synchronized boolean ensureKey() {
        if (runtimeKeyConfigured) {
            return false;
        }
        if (Files.exists(keyPath)) {
            return false;
        }
        try {
            Path parent = keyPath.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
                trySetPosix(parent, DIR_PERMS);
            }
            String base64 = generateBase64Key();
            Files.writeString(keyPath, base64);
            trySetPosix(keyPath, FILE_PERMS);
            log.info("setup.crypto.key.generated path={}", keyPath);
            return true;
        } catch (IOException | NoSuchAlgorithmException ex) {
            throw new IllegalStateException("Failed to generate crypto key at " + keyPath, ex);
        }
    }

    private String generateBase64Key() throws NoSuchAlgorithmException {
        KeyGenerator generator = KeyGenerator.getInstance("AES");
        generator.init(AES_BITS);
        SecretKey key = generator.generateKey();
        return Base64.getEncoder().encodeToString(key.getEncoded());
    }

    private void trySetPosix(Path path, Set<PosixFilePermission> perms) {
        try {
            Files.setPosixFilePermissions(path, perms);
        } catch (UnsupportedOperationException | IOException ignored) {
            // Non-POSIX filesystem (e.g. Windows dev). Permissions not enforceable.
        }
    }
}
