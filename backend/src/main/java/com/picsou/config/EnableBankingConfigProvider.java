package com.picsou.config;

import com.picsou.service.EnableBankingKeyPairService;
import com.picsou.service.SetupService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.Optional;

/**
 * Single resolver for Enable Banking connection config. Reads the user-writable
 * fields (app-id, key-id, redirect URI, private-key path) from
 * {@code app_setting} with env-var fallback, so:
 *
 * <ul>
 *   <li>Old installs that set {@code ENABLEBANKING_*} via env keep working
 *       verbatim — no migration required.</li>
 *   <li>New installs write the same fields via the setup wizard and the
 *       connector reads them on the next request without a restart.</li>
 * </ul>
 *
 * <p>No caching of the parsed {@link PrivateKey} — the key is read on each
 * call. Enable Banking sync runs daily (scheduled), so the few milliseconds
 * of parsing per run are invisible. Skipping the cache also means a
 * rotated key takes effect on the very next call, which matters for
 * operator recovery flows.
 */
@Component
public class EnableBankingConfigProvider {

    private final SetupService setupService;
    private final String envApplicationId;
    private final String envKeyId;
    private final String envRedirectUri;
    private final String envPrivateKeyPem;
    private final String envPrivateKeyPath;
    private final String defaultPrivateKeyPath;

    public EnableBankingConfigProvider(
        SetupService setupService,
        EnableBankingKeyPairService keyPairService,
        @Value("${app.enablebanking.application-id:}") String envApplicationId,
        @Value("${app.enablebanking.key-id:}") String envKeyId,
        @Value("${app.enablebanking.redirect-uri:}") String envRedirectUri,
        @Value("${app.enablebanking.private-key:}") String envPrivateKeyPem,
        @Value("${app.enablebanking.private-key-path:}") String envPrivateKeyPath
    ) {
        this.setupService = setupService;
        this.envApplicationId = envApplicationId;
        this.envKeyId = envKeyId;
        this.envRedirectUri = envRedirectUri;
        this.envPrivateKeyPem = envPrivateKeyPem;
        this.envPrivateKeyPath = envPrivateKeyPath;
        this.defaultPrivateKeyPath = keyPairService.privateKeyPath().toString();
    }

    public Optional<String> applicationId() {
        return resolve(SetupService.KEY_ENABLEBANKING_APP_ID, envApplicationId);
    }

    /**
     * The JWT signing Key ID. Per Enable Banking's spec the {@code kid} is the
     * application's own ID, so when no key-id is configured separately we fall
     * back to {@link #applicationId()}. An explicitly-set value (legacy env
     * {@code ENABLEBANKING_KEY_ID} or a stored {@code key-id} row) still wins,
     * keeping old installs working verbatim.
     */
    public Optional<String> keyId() {
        return resolve(SetupService.KEY_ENABLEBANKING_KEY_ID, envKeyId).or(this::applicationId);
    }

    public Optional<String> redirectUri() {
        return resolve(SetupService.KEY_ENABLEBANKING_REDIRECT_URI, envRedirectUri);
    }

    /**
     * Loads the private key, preferring the wizard-generated file at
     * {@link EnableBankingKeyPairService#privateKeyPath()}, then the
     * env-driven {@code ENABLEBANKING_PRIVATE_KEY_PATH}, then the inline
     * {@code ENABLEBANKING_PRIVATE_KEY} PEM.
     */
    public Optional<PrivateKey> privateKey() {
        if (Files.exists(Path.of(defaultPrivateKeyPath))) {
            return Optional.of(parsePem(read(defaultPrivateKeyPath)));
        }
        if (!envPrivateKeyPath.isBlank() && Files.exists(Path.of(envPrivateKeyPath))) {
            return Optional.of(parsePem(read(envPrivateKeyPath)));
        }
        if (!envPrivateKeyPem.isBlank()) {
            return Optional.of(parsePem(envPrivateKeyPem));
        }
        return Optional.empty();
    }

    public boolean isConfigured() {
        return applicationId().isPresent()
            && keyId().isPresent()
            && redirectUri().isPresent()
            && privateKey().isPresent();
    }

    /**
     * Cheap, non-parsing check of whether a private key is available — mirrors
     * the source precedence of {@link #privateKey()} but only tests for
     * existence / non-blank content. Used by status surfaces (e.g. the admin
     * settings page) so a malformed key reports as "present" rather than
     * throwing and 500-ing the page; {@link #privateKey()} surfaces the parse
     * error at actual use time instead.
     */
    public boolean privateKeyPresent() {
        return Files.exists(Path.of(defaultPrivateKeyPath))
            || (!envPrivateKeyPath.isBlank() && Files.exists(Path.of(envPrivateKeyPath)))
            || !envPrivateKeyPem.isBlank();
    }

    // ─── internals ───────────────────────────────────────────────────────────

    private Optional<String> resolve(String settingKey, String envValue) {
        return setupService.readSetting(settingKey)
            .filter(s -> !s.isBlank())
            .or(() -> envValue.isBlank() ? Optional.empty() : Optional.of(envValue));
    }

    private static String read(String path) {
        try {
            return Files.readString(Path.of(path));
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to read private key from " + path, ex);
        }
    }

    private static PrivateKey parsePem(String pem) {
        try {
            String cleaned = pem
                .replace("\\n", "\n")
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
            byte[] der = Base64.getDecoder().decode(cleaned);
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (Exception ex) {
            throw new IllegalStateException("Enable Banking private key is not a valid PKCS8 PEM.", ex);
        }
    }
}
