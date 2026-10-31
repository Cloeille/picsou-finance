package com.picsou.config;

import com.picsou.model.AppUser;
import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.UUID;

@Component
public class JwtUtil {

    private final SecretKey signingKey;
    private final long accessExpiryMinutes;
    private final long refreshExpiryDays;
    private final long mfaChallengeExpiryMinutes;

    public JwtUtil(
        @Value("${app.jwt.secret}") String secret,
        @Value("${app.jwt.access-token-expiry-minutes:15}") long accessExpiryMinutes,
        @Value("${app.jwt.refresh-token-expiry-days:7}") long refreshExpiryDays,
        @Value("${app.jwt.mfa-challenge-expiry-minutes:5}") long mfaChallengeExpiryMinutes
    ) {
        // Ensure the key is at least 256 bits for HS256
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < 32) {
            throw new IllegalStateException("JWT_SECRET must be at least 32 characters (256 bits)");
        }
        this.signingKey = Keys.hmacShaKeyFor(keyBytes);
        this.accessExpiryMinutes = accessExpiryMinutes;
        this.refreshExpiryDays = refreshExpiryDays;
        this.mfaChallengeExpiryMinutes = mfaChallengeExpiryMinutes;
    }

    public String generateAccessToken(AppUser user) {
        return buildToken(user, "access", Instant.now().plus(accessExpiryMinutes, ChronoUnit.MINUTES), null);
    }

    public String generateRefreshToken(AppUser user) {
        return generateRefreshToken(user, null);
    }

    /**
     * Refresh token optionally bound to a "Remember Me" persistent-session series via a
     * {@code sid} claim. The binding lets {@code /auth/refresh} reject a refresh chain whose
     * series has been revoked ("log out this device") or has hit its 90-day cap, even though
     * the JWT itself has not yet expired. Pass {@code null} for a session-scoped login not
     * tied to any persistent session.
     */
    public String generateRefreshToken(AppUser user, UUID seriesId) {
        return buildToken(user, "refresh", Instant.now().plus(refreshExpiryDays, ChronoUnit.DAYS), seriesId);
    }

    /**
     * Short-lived intermediate token issued after password verification when the user
     * has 2FA enabled. The client presents it to {@code POST /api/auth/mfa/verify}
     * along with a TOTP/recovery code; on success the access+refresh cookies are set.
     * The {@code remember_me} claim preserves the user's original checkbox state.
     */
    public String generateMfaChallengeToken(AppUser user, boolean rememberMe) {
        Instant expiry = Instant.now().plus(mfaChallengeExpiryMinutes, ChronoUnit.MINUTES);
        return Jwts.builder()
            .subject(user.getUsername())
            .claim("uid", user.getId())
            .claim("role", user.getRole().name())
            .claim("type", "mfa_challenge")
            .claim("remember_me", rememberMe)
            .issuedAt(Date.from(Instant.now()))
            .expiration(Date.from(expiry))
            .signWith(signingKey)
            .compact();
    }

    private String buildToken(AppUser user, String tokenType, Instant expiry, UUID seriesId) {
        JwtBuilder builder = Jwts.builder()
            .subject(user.getUsername())
            .claim("uid", user.getId())
            .claim("role", user.getRole().name())
            .claim("type", tokenType)
            .claim("tv", user.getTokenVersion())
            .issuedAt(Date.from(Instant.now()))
            .expiration(Date.from(expiry))
            .signWith(signingKey);
        if (seriesId != null) {
            builder.claim("sid", seriesId.toString());
        }
        return builder.compact();
    }

    /**
     * The persistent-session series this refresh token is bound to, or {@code null} if the
     * token carries no {@code sid} claim (a session-scoped, non-"Remember Me" refresh) or the
     * claim is malformed.
     */
    public UUID getSeriesId(Claims claims) {
        String sid = claims.get("sid", String.class);
        if (sid == null) return null;
        try {
            return UUID.fromString(sid);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    public Long getTokenVersion(Claims claims) {
        Number v = claims.get("tv", Number.class);
        return v == null ? null : v.longValue();
    }

    /**
     * The account the token was minted for, by its immutable id. This, not the username in
     * {@code sub}, is what a token identifies: a username can be changed or freed and reused.
     */
    public Long getUserId(Claims claims) {
        Number v = claims.get("uid", Number.class);
        return v == null ? null : v.longValue();
    }

    public Claims validateAndParse(String token) {
        return Jwts.parser()
            .verifyWith(signingKey)
            .build()
            .parseSignedClaims(token)
            .getPayload();
    }

    public boolean isRefreshToken(Claims claims) {
        return "refresh".equals(claims.get("type", String.class));
    }

    public boolean isAccessToken(Claims claims) {
        return "access".equals(claims.get("type", String.class));
    }

    public boolean isMfaChallengeToken(Claims claims) {
        return "mfa_challenge".equals(claims.get("type", String.class));
    }

    public boolean getRememberMeClaim(Claims claims) {
        Boolean v = claims.get("remember_me", Boolean.class);
        return v != null && v;
    }

    public long getRefreshExpirySeconds() {
        return refreshExpiryDays * 24 * 60 * 60;
    }

    public long getAccessExpirySeconds() {
        return accessExpiryMinutes * 60;
    }

    public long getMfaChallengeExpirySeconds() {
        return mfaChallengeExpiryMinutes * 60;
    }
}
