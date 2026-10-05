package com.picsou.service;

import com.picsou.model.AppUser;
import com.picsou.model.PersistentSession;
import com.picsou.repository.PersistentSessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class PersistentSessionService {

    private static final Logger log = LoggerFactory.getLogger(PersistentSessionService.class);

    private static final int RAW_TOKEN_BYTES = 64;
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final PersistentSessionRepository repository;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final long expiryDays;
    private final long rotationGraceSeconds;

    public PersistentSessionService(
        PersistentSessionRepository repository,
        Clock clock,
        @Value("${app.persistent-session.expiry-days:90}") long expiryDays,
        @Value("${app.persistent-session.rotation-grace-seconds:30}") long rotationGraceSeconds
    ) {
        this.repository = repository;
        this.clock = clock;
        this.expiryDays = expiryDays;
        this.rotationGraceSeconds = rotationGraceSeconds;
    }

    @Transactional
    public IssueResult issue(AppUser user, boolean trustedFor2fa, String userAgent, String remoteAddr) {
        UUID seriesId = UUID.randomUUID();
        String token = generateToken();
        Instant now = Instant.now(clock);
        PersistentSession session = PersistentSession.builder()
            .seriesId(seriesId)
            .tokenHash(sha256Hex(token))
            .user(user)
            .userAgent(truncate(userAgent, 255))
            .ipPrefix(ipPrefix(remoteAddr))
            .trustedFor2fa(trustedFor2fa)
            .createdAt(now)
            .lastUsedAt(now)
            .expiresAt(now.plus(expiryDays, ChronoUnit.DAYS))
            .build();
        repository.save(session);
        return new IssueResult(formatCookieValue(seriesId, token), session);
    }

    /**
     * Validate the cookie value, rotate the token, and return the rotated cookie value + session.
     * <p>
     * Token theft detection: if the cookie's series_id exists but its token hash matches neither
     * the current stored hash nor the immediately-previous hash within the rotation grace window,
     * the entire series is revoked and an empty Optional is returned.
     * <p>
     * Grace window: several browser tabs restored at once each present the same pre-rotation
     * token. The first request rotates it; without a grace window the rest would look like a
     * replayed (stolen) token and revoke the series, logging the user out everywhere. Accepting
     * the immediately-previous token for {@code rotationGraceSeconds} tolerates that concurrent
     * burst. The persistent cookie is shared across tabs and converges on the latest rotated
     * value, so the window only needs to cover the in-flight burst, not a lasting second token.
     */
    @Transactional
    public Optional<ValidationResult> validateAndRotate(String cookieValue) {
        ParsedCookie parsed = parseCookie(cookieValue).orElse(null);
        if (parsed == null) return Optional.empty();

        // Row-level lock so concurrent restores of the same series serialize —
        // otherwise both read the same pre-rotation state and the second rotation
        // orphans the first token, which then trips theft detection below.
        Optional<PersistentSession> opt = repository.findBySeriesIdForUpdate(parsed.seriesId());
        if (opt.isEmpty()) return Optional.empty();

        PersistentSession session = opt.get();
        Instant now = Instant.now(clock);

        if (!session.isActive(now)) return Optional.empty();

        String presentedHash = sha256Hex(parsed.token());
        boolean matchesCurrent = constantTimeEquals(presentedHash, session.getTokenHash());
        boolean matchesRecentPrevious =
            session.getPreviousTokenHash() != null
            && session.getPreviousTokenAt() != null
            && !now.isAfter(session.getPreviousTokenAt().plusSeconds(rotationGraceSeconds))
            && constantTimeEquals(presentedHash, session.getPreviousTokenHash());

        if (!matchesCurrent && !matchesRecentPrevious) {
            log.warn("Persistent token theft suspected: series={} user={} — wiping series",
                session.getSeriesId(), session.getUser().getId());
            session.setRevokedAt(now);
            repository.save(session);
            return Optional.empty();
        }

        String newToken = generateToken();
        if (matchesCurrent) {
            // A genuine rotation: arm the grace window with the token we're rotating
            // away from, so concurrent tabs still holding it are accepted below.
            session.setPreviousTokenHash(session.getTokenHash());
            session.setPreviousTokenAt(now);
        }
        // On a grace (previous-match) acceptance we deliberately leave
        // previous_token_hash/at ANCHORED. Two consequences, both intended:
        //   - EVERY concurrent tab presenting the same pre-rotation token is
        //     accepted (not just the first two), because `previous` keeps pointing
        //     at that token instead of advancing to each freshly-minted one;
        //   - the window can't be slid forward by replaying the previous token —
        //     it stays pinned to the rotation that opened it (rotationGraceSeconds
        //     from the first rotation, not from the last acceptance).
        session.setTokenHash(sha256Hex(newToken));
        session.setLastUsedAt(now);
        repository.save(session);
        return Optional.of(new ValidationResult(formatCookieValue(session.getSeriesId(), newToken), session));
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(
            a.getBytes(StandardCharsets.UTF_8),
            b.getBytes(StandardCharsets.UTF_8));
    }

    public List<PersistentSession> listActiveForUser(AppUser user) {
        return repository.findByUserIdAndRevokedAtIsNullOrderByLastUsedAtDesc(user.getId());
    }

    @Transactional
    public int revokeAllForUser(Long userId) {
        return repository.revokeAllByUserId(userId, Instant.now(clock));
    }

    @Transactional
    public int revokeAllForUserExcept(Long userId, Long exceptId) {
        return repository.revokeAllByUserIdExcept(userId, exceptId, Instant.now(clock));
    }

    @Transactional
    public boolean revoke(Long sessionId, AppUser owner) {
        Optional<PersistentSession> opt = repository.findById(sessionId);
        if (opt.isEmpty()) return false;
        PersistentSession session = opt.get();
        if (!session.getUser().getId().equals(owner.getId())) return false;
        if (session.getRevokedAt() != null) return true;
        session.setRevokedAt(Instant.now(clock));
        repository.save(session);
        return true;
    }

    @Transactional
    public void revokeBySeriesId(UUID seriesId) {
        repository.revokeBySeriesId(seriesId, Instant.now(clock));
    }

    public Optional<UUID> seriesFromCookie(String cookieValue) {
        return parseCookie(cookieValue).map(ParsedCookie::seriesId);
    }

    /**
     * Returns true iff the cookie's series_id belongs to {@code user}, the
     * session is currently active, and {@code trusted_for_2fa = true}. Does NOT
     * validate the token hash — call this only AFTER the request has already
     * been authenticated by other means (typically: the persistent filter set
     * SecurityContext on this same request, having validated the hash itself).
     */
    public boolean isTrustedDeviceFor(AppUser user, String cookieValue) {
        return parseCookie(cookieValue)
            .flatMap(p -> repository.findBySeriesId(p.seriesId()))
            .filter(s -> s.getUser().getId().equals(user.getId()))
            .filter(PersistentSession::isTrustedFor2fa)
            .filter(s -> s.isActive(Instant.now(clock)))
            .isPresent();
    }

    /**
     * Resolves the owning user id of a "Remember Me" cookie by its series_id,
     * WITHOUT validating the token hash. Intended only for cheap "does this
     * left-over cookie belong to the user we're authenticating?" checks — never
     * for granting access. Empty if the cookie is malformed or the series is
     * unknown (in which case the caller should treat it as foreign).
     */
    public Optional<Long> ownerUserId(String cookieValue) {
        return parseCookie(cookieValue)
            .flatMap(p -> repository.findBySeriesId(p.seriesId()))
            .map(s -> s.getUser().getId());
    }

    /**
     * Whether {@code seriesId} names a persistent session that is currently active — i.e.
     * neither revoked ("log out this device"/"log out everywhere else") nor past its 90-day
     * expiry. Used by {@code /auth/refresh} to cut a refresh chain bound to a series the user
     * has revoked, even while the refresh JWT itself is still cryptographically valid. Unknown
     * series → {@code false} (treat as no longer valid).
     */
    public boolean isSeriesActive(UUID seriesId) {
        return repository.findBySeriesId(seriesId)
            .filter(s -> s.isActive(Instant.now(clock)))
            .isPresent();
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private String generateToken() {
        byte[] buf = new byte[RAW_TOKEN_BYTES];
        random.nextBytes(buf);
        return URL_ENCODER.encodeToString(buf);
    }

    static String sha256Hex(String token) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    static String formatCookieValue(UUID seriesId, String token) {
        return seriesId.toString() + ":" + token;
    }

    static Optional<ParsedCookie> parseCookie(String cookieValue) {
        if (cookieValue == null) return Optional.empty();
        int idx = cookieValue.indexOf(':');
        if (idx <= 0 || idx == cookieValue.length() - 1) return Optional.empty();
        try {
            UUID series = UUID.fromString(cookieValue.substring(0, idx));
            String token = cookieValue.substring(idx + 1);
            return Optional.of(new ParsedCookie(series, token));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    static String ipPrefix(String remoteAddr) {
        if (remoteAddr == null || remoteAddr.isBlank()) return null;
        if (remoteAddr.contains(".")) {
            // IPv4: keep first 3 octets ("/24") for "trusted device" identity.
            int dot1 = remoteAddr.indexOf('.');
            int dot2 = remoteAddr.indexOf('.', dot1 + 1);
            int dot3 = remoteAddr.indexOf('.', dot2 + 1);
            if (dot3 < 0) return remoteAddr;
            return remoteAddr.substring(0, dot3 + 1);
        }
        // IPv6: keep the first 4 hextets ("/64") joined by ":".
        String[] parts = remoteAddr.split(":");
        StringBuilder sb = new StringBuilder();
        int kept = 0;
        for (String part : parts) {
            if (sb.length() > 0) sb.append(':');
            sb.append(part);
            if (!part.isEmpty()) kept++;
            if (kept >= 4) break;
        }
        return sb.toString();
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    public record IssueResult(String cookieValue, PersistentSession session) {}

    public record ValidationResult(String rotatedCookieValue, PersistentSession session) {}

    record ParsedCookie(UUID seriesId, String token) {}
}
