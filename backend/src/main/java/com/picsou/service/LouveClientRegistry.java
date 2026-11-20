package com.picsou.service;

import com.picsou.adapter.LouveOAuthClient;
import com.picsou.model.AppSetting;
import com.picsou.repository.AppSettingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * One Louve Invest client registration per redirect URI, made on first use and kept in
 * {@code app_setting}. The redirect URI comes from the instance's configured origins, so there is
 * one registration per configured origin a member connects from. {@link #forget} drops one Louve
 * stopped recognising.
 *
 * <p>The key is {@code louve.client-id:} plus the SHA-256 of the redirect URI in hex (80
 * characters): {@code setting_key} is a {@code VARCHAR(100)} and a redirect URI can be longer.
 * The value is the client id; a public client has no secret.
 */
@Service
public class LouveClientRegistry {

    static final String KEY_PREFIX = "louve.client-id:";

    private static final Logger log = LoggerFactory.getLogger(LouveClientRegistry.class);

    private final AppSettingRepository settings;
    private final LouveOAuthClient oauth;
    private final TransactionTemplate tx;

    public LouveClientRegistry(AppSettingRepository settings, LouveOAuthClient oauth, TransactionTemplate tx) {
        this.settings = settings;
        this.oauth = oauth;
        this.tx = tx;
    }

    /**
     * The stored client id for {@code redirectUri}, registering one when there is none. The
     * registration call runs outside any transaction; of two registrations racing for the same
     * URI (another instance), the first one stored is kept and returned to both.
     */
    public synchronized String clientIdFor(String redirectUri) {
        String key = key(redirectUri);
        return settings.findByKey(key).map(AppSetting::getValue).orElseGet(() -> register(key, redirectUri));
    }

    /**
     * Drops the registration of {@code redirectUri} if it still holds the refused client id: a
     * newer registration (another member reconnected first) is kept, and a second instance
     * forgetting the same client finds 0 rows instead of failing.
     */
    public synchronized void forget(String redirectUri, String refusedClientId) {
        if (redirectUri == null || refusedClientId == null || refusedClientId.isBlank()) {
            return;
        }
        Integer removed = tx.execute(status -> settings.deleteIfValue(key(redirectUri), refusedClientId));
        if (removed != null && removed > 0) {
            log.info("Dropped the Louve Invest client registration Louve no longer recognises");
        }
    }

    private String register(String key, String redirectUri) {
        String clientId = oauth.register(redirectUri);
        String stored = tx.execute(status -> {
            settings.insertIfAbsent(key, clientId);
            return settings.findByKey(key).map(AppSetting::getValue).orElse(clientId);
        });
        if (clientId.equals(stored)) {
            log.info("Registered Picsou as a Louve Invest client");
        }
        return stored;
    }

    static String key(String redirectUri) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(redirectUri.getBytes(StandardCharsets.UTF_8));
            return KEY_PREFIX + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }
}
