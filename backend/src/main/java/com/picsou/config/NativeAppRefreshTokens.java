package com.picsou.config;

import org.springframework.security.crypto.keygen.Base64StringKeyGenerator;
import org.springframework.security.crypto.keygen.StringKeyGenerator;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Optional;

/**
 * Refresh-token values for the native app that name the authorization they belong to, so a
 * rotated-away token can be traced back to its authorization when it is presented again (reuse
 * detection, RFC 9700 §4.14.2 — see {@link RefreshTokenReuseDetector}).
 *
 * <p>Format: {@code <authorizationId>.<random>.<mac>}, where the MAC is an HMAC-SHA256 over the
 * first two parts keyed by {@code JWT_SECRET}. The framework still stores and looks tokens up by
 * their full value; the MAC only matters for a value it no longer knows, and proves this server
 * issued it, so nobody can forge {@code <someone's aid>.x.y} to revoke a sign-in that isn't theirs.
 */
final class NativeAppRefreshTokens {

    private static final String MAC_ALGORITHM = "HmacSHA256";
    /** Keeps these MACs apart from the HS256 JWT signatures made with the same secret. */
    private static final String MAC_DOMAIN = "picsou-ios-refresh-token|";

    private final SecretKeySpec key;
    private final StringKeyGenerator random = new Base64StringKeyGenerator(Base64.getUrlEncoder().withoutPadding(), 32);

    NativeAppRefreshTokens(String secret) {
        this.key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), MAC_ALGORITHM);
    }

    String issue(String authorizationId) {
        String body = authorizationId + "." + random.generateKey();
        return body + "." + mac(body);
    }

    /** The authorization id of a token this server issued, empty for any other value. */
    Optional<String> issuedFor(String token) {
        int firstDot = token.indexOf('.');
        int lastDot = token.lastIndexOf('.');
        if (firstDot <= 0 || lastDot == firstDot) {
            return Optional.empty();
        }
        String body = token.substring(0, lastDot);
        boolean authentic = MessageDigest.isEqual(
            mac(body).getBytes(StandardCharsets.US_ASCII),
            token.substring(lastDot + 1).getBytes(StandardCharsets.US_ASCII));
        return authentic ? Optional.of(token.substring(0, firstDot)) : Optional.empty();
    }

    private String mac(String body) {
        try {
            Mac mac = Mac.getInstance(MAC_ALGORITHM);
            mac.init(key);
            byte[] digest = mac.doFinal((MAC_DOMAIN + body).getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(MAC_ALGORITHM + " unavailable", e);
        }
    }
}
