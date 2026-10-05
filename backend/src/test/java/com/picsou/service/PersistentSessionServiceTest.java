package com.picsou.service;

import com.picsou.model.AppUser;
import com.picsou.model.PersistentSession;
import com.picsou.repository.PersistentSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PersistentSessionServiceTest {

    private static final Instant NOW = Instant.parse("2026-04-26T10:00:00Z");

    @Mock PersistentSessionRepository repository;

    PersistentSessionService service;
    AppUser user;

    @BeforeEach
    void setUp() {
        Clock fixed = Clock.fixed(NOW, ZoneOffset.UTC);
        service = new PersistentSessionService(repository, fixed, 90, 30);
        user = AppUser.builder().id(99L).username("alice").passwordHash("h").build();
    }

    // ─── issue ─────────────────────────────────────────────────────────────

    @Test
    void issue_persistsSessionWithHashedTokenAndExpiry() {
        when(repository.save(any(PersistentSession.class))).thenAnswer(inv -> inv.getArgument(0));

        PersistentSessionService.IssueResult result =
            service.issue(user, true, "Mozilla/5.0", "192.168.1.42");

        assertThat(result.cookieValue()).contains(":");
        String[] parts = result.cookieValue().split(":", 2);
        UUID series = UUID.fromString(parts[0]);
        String token = parts[1];

        ArgumentCaptor<PersistentSession> captor = ArgumentCaptor.forClass(PersistentSession.class);
        verify(repository).save(captor.capture());
        PersistentSession saved = captor.getValue();

        assertThat(saved.getSeriesId()).isEqualTo(series);
        assertThat(saved.getTokenHash()).isEqualTo(PersistentSessionService.sha256Hex(token));
        assertThat(saved.getTokenHash()).doesNotContain(token); // raw token is NOT stored
        assertThat(saved.isTrustedFor2fa()).isTrue();
        assertThat(saved.getUserAgent()).isEqualTo("Mozilla/5.0");
        assertThat(saved.getIpPrefix()).isEqualTo("192.168.1.");
        assertThat(saved.getExpiresAt()).isEqualTo(NOW.plus(90, ChronoUnit.DAYS));
        assertThat(saved.getCreatedAt()).isEqualTo(NOW);
        assertThat(saved.getLastUsedAt()).isEqualTo(NOW);
        assertThat(saved.getRevokedAt()).isNull();
    }

    @Test
    void issue_truncatesLongUserAgent() {
        when(repository.save(any(PersistentSession.class))).thenAnswer(inv -> inv.getArgument(0));
        String longUa = "a".repeat(300);

        service.issue(user, false, longUa, "10.0.0.5");

        ArgumentCaptor<PersistentSession> captor = ArgumentCaptor.forClass(PersistentSession.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getUserAgent()).hasSize(255);
    }

    // ─── validateAndRotate ────────────────────────────────────────────────

    @Test
    void validateAndRotate_acceptsValidTokenAndRotatesHash() {
        UUID series = UUID.randomUUID();
        String token = "raw-token-value";
        PersistentSession session = PersistentSession.builder()
            .id(1L).seriesId(series).user(user)
            .tokenHash(PersistentSessionService.sha256Hex(token))
            .createdAt(NOW.minus(10, ChronoUnit.DAYS))
            .lastUsedAt(NOW.minus(5, ChronoUnit.DAYS))
            .expiresAt(NOW.plus(80, ChronoUnit.DAYS))
            .build();

        when(repository.findBySeriesIdForUpdate(series)).thenReturn(Optional.of(session));
        when(repository.save(any(PersistentSession.class))).thenAnswer(inv -> inv.getArgument(0));

        Optional<PersistentSessionService.ValidationResult> out =
            service.validateAndRotate(series + ":" + token);

        assertThat(out).isPresent();
        String oldHash = PersistentSessionService.sha256Hex(token);
        assertThat(session.getTokenHash()).isNotEqualTo(oldHash);
        assertThat(session.getLastUsedAt()).isEqualTo(NOW);

        // Rotated cookie value uses the same series + a new token whose hash matches
        String[] parts = out.get().rotatedCookieValue().split(":", 2);
        assertThat(parts[0]).isEqualTo(series.toString());
        assertThat(PersistentSessionService.sha256Hex(parts[1])).isEqualTo(session.getTokenHash());
    }

    @Test
    void validateAndRotate_replayedTokenWipesSeries() {
        UUID series = UUID.randomUUID();
        String stolenOldToken = "stolen-old-token";
        // Server has already rotated to a NEW hash; the attacker presents the OLD token.
        PersistentSession session = PersistentSession.builder()
            .id(1L).seriesId(series).user(user)
            .tokenHash(PersistentSessionService.sha256Hex("current-rotated-token"))
            .createdAt(NOW.minus(10, ChronoUnit.DAYS))
            .lastUsedAt(NOW.minus(1, ChronoUnit.HOURS))
            .expiresAt(NOW.plus(80, ChronoUnit.DAYS))
            .build();

        when(repository.findBySeriesIdForUpdate(series)).thenReturn(Optional.of(session));
        when(repository.save(any(PersistentSession.class))).thenAnswer(inv -> inv.getArgument(0));

        Optional<PersistentSessionService.ValidationResult> out =
            service.validateAndRotate(series + ":" + stolenOldToken);

        assertThat(out).isEmpty();
        assertThat(session.getRevokedAt()).isEqualTo(NOW); // series wiped
        verify(repository, times(1)).save(session);
    }

    @Test
    void validateAndRotate_acceptsRecentPreviousToken_forConcurrentMultiTabRestore() {
        UUID series = UUID.randomUUID();
        String previousToken = "the-just-superseded-token";
        // The series was rotated 10s ago (within the 30s grace window): a second tab
        // restoring at the same time still holds the pre-rotation token.
        PersistentSession session = PersistentSession.builder()
            .id(1L).seriesId(series).user(user)
            .tokenHash(PersistentSessionService.sha256Hex("current-token"))
            .previousTokenHash(PersistentSessionService.sha256Hex(previousToken))
            .previousTokenAt(NOW.minus(10, ChronoUnit.SECONDS))
            .createdAt(NOW.minus(10, ChronoUnit.DAYS))
            .lastUsedAt(NOW.minus(10, ChronoUnit.SECONDS))
            .expiresAt(NOW.plus(80, ChronoUnit.DAYS))
            .build();
        when(repository.findBySeriesIdForUpdate(series)).thenReturn(Optional.of(session));
        when(repository.save(any(PersistentSession.class))).thenAnswer(inv -> inv.getArgument(0));

        Instant prevAtBefore = session.getPreviousTokenAt();
        String prevHashBefore = session.getPreviousTokenHash();

        Optional<PersistentSessionService.ValidationResult> out =
            service.validateAndRotate(series + ":" + previousToken);

        // Accepted (not revoked) and rotated to a fresh token.
        assertThat(out).isPresent();
        assertThat(session.getRevokedAt()).isNull();
        String[] parts = out.get().rotatedCookieValue().split(":", 2);
        assertThat(PersistentSessionService.sha256Hex(parts[1])).isEqualTo(session.getTokenHash());
        // The grace window is ANCHORED: a previous-token acceptance must not advance
        // previous_token_* (else it would slide the window and only cover 2 tabs).
        assertThat(session.getPreviousTokenAt()).isEqualTo(prevAtBefore);
        assertThat(session.getPreviousTokenHash()).isEqualTo(prevHashBefore);
    }

    @Test
    void validateAndRotate_acceptsAllTabsInAConcurrentBurst_notJustTwo() {
        // Three tabs restored at once all present the same pre-rotation token. With
        // the rotate path serialized (findBySeriesIdForUpdate) the calls run in order,
        // and because `previous` stays anchored to that token, all three are accepted.
        UUID series = UUID.randomUUID();
        String burstToken = "the-shared-pre-rotation-token";
        PersistentSession session = PersistentSession.builder()
            .id(1L).seriesId(series).user(user)
            .tokenHash(PersistentSessionService.sha256Hex(burstToken))
            .createdAt(NOW.minus(10, ChronoUnit.DAYS))
            .lastUsedAt(NOW.minus(5, ChronoUnit.DAYS))
            .expiresAt(NOW.plus(80, ChronoUnit.DAYS))
            .build();
        when(repository.findBySeriesIdForUpdate(series)).thenReturn(Optional.of(session));
        when(repository.save(any(PersistentSession.class))).thenAnswer(inv -> inv.getArgument(0));

        for (int tab = 0; tab < 3; tab++) {
            Optional<PersistentSessionService.ValidationResult> out =
                service.validateAndRotate(series + ":" + burstToken);
            assertThat(out).as("tab %s must be accepted, not revoked", tab).isPresent();
        }
        assertThat(session.getRevokedAt()).isNull();
    }

    @Test
    void validateAndRotate_foreignTokenWithinWindowIsStillTheft() {
        // A token that matches NEITHER current nor the (in-window) previous hash is a
        // genuine replay — the grace window must not weaken that.
        UUID series = UUID.randomUUID();
        PersistentSession session = PersistentSession.builder()
            .id(1L).seriesId(series).user(user)
            .tokenHash(PersistentSessionService.sha256Hex("current-token"))
            .previousTokenHash(PersistentSessionService.sha256Hex("previous-token"))
            .previousTokenAt(NOW.minus(5, ChronoUnit.SECONDS)) // within the 30s window
            .createdAt(NOW.minus(10, ChronoUnit.DAYS))
            .lastUsedAt(NOW.minus(5, ChronoUnit.SECONDS))
            .expiresAt(NOW.plus(80, ChronoUnit.DAYS))
            .build();
        when(repository.findBySeriesIdForUpdate(series)).thenReturn(Optional.of(session));
        when(repository.save(any(PersistentSession.class))).thenAnswer(inv -> inv.getArgument(0));

        Optional<PersistentSessionService.ValidationResult> out =
            service.validateAndRotate(series + ":a-completely-unrelated-token");

        assertThat(out).isEmpty();
        assertThat(session.getRevokedAt()).isEqualTo(NOW); // series wiped
    }

    @Test
    void validateAndRotate_previousTokenAfterGraceWindowIsTreatedAsTheft() {
        UUID series = UUID.randomUUID();
        String previousToken = "an-old-superseded-token";
        // The previous token was superseded 60s ago — beyond the 30s grace window.
        PersistentSession session = PersistentSession.builder()
            .id(1L).seriesId(series).user(user)
            .tokenHash(PersistentSessionService.sha256Hex("current-token"))
            .previousTokenHash(PersistentSessionService.sha256Hex(previousToken))
            .previousTokenAt(NOW.minus(60, ChronoUnit.SECONDS))
            .createdAt(NOW.minus(10, ChronoUnit.DAYS))
            .lastUsedAt(NOW.minus(60, ChronoUnit.SECONDS))
            .expiresAt(NOW.plus(80, ChronoUnit.DAYS))
            .build();
        when(repository.findBySeriesIdForUpdate(series)).thenReturn(Optional.of(session));
        when(repository.save(any(PersistentSession.class))).thenAnswer(inv -> inv.getArgument(0));

        Optional<PersistentSessionService.ValidationResult> out =
            service.validateAndRotate(series + ":" + previousToken);

        assertThat(out).isEmpty();
        assertThat(session.getRevokedAt()).isEqualTo(NOW); // series wiped
    }

    @Test
    void validateAndRotate_rememberPreviousHashOnRotation() {
        UUID series = UUID.randomUUID();
        String token = "raw-token-value";
        String currentHash = PersistentSessionService.sha256Hex(token);
        PersistentSession session = PersistentSession.builder()
            .id(1L).seriesId(series).user(user)
            .tokenHash(currentHash)
            .createdAt(NOW.minus(10, ChronoUnit.DAYS))
            .lastUsedAt(NOW.minus(5, ChronoUnit.DAYS))
            .expiresAt(NOW.plus(80, ChronoUnit.DAYS))
            .build();
        when(repository.findBySeriesIdForUpdate(series)).thenReturn(Optional.of(session));
        when(repository.save(any(PersistentSession.class))).thenAnswer(inv -> inv.getArgument(0));

        service.validateAndRotate(series + ":" + token);

        // The just-rotated-away token is remembered so a concurrent tab can still use it.
        assertThat(session.getPreviousTokenHash()).isEqualTo(currentHash);
        assertThat(session.getPreviousTokenAt()).isEqualTo(NOW);
    }

    @Test
    void validateAndRotate_expiredSessionReturnsEmpty() {
        UUID series = UUID.randomUUID();
        String token = "raw-token";
        PersistentSession session = PersistentSession.builder()
            .id(1L).seriesId(series).user(user)
            .tokenHash(PersistentSessionService.sha256Hex(token))
            .createdAt(NOW.minus(100, ChronoUnit.DAYS))
            .lastUsedAt(NOW.minus(95, ChronoUnit.DAYS))
            .expiresAt(NOW.minus(10, ChronoUnit.DAYS))
            .build();
        when(repository.findBySeriesIdForUpdate(series)).thenReturn(Optional.of(session));

        Optional<PersistentSessionService.ValidationResult> out =
            service.validateAndRotate(series + ":" + token);

        assertThat(out).isEmpty();
        verify(repository, never()).save(any());
    }

    @Test
    void validateAndRotate_revokedSessionReturnsEmpty() {
        UUID series = UUID.randomUUID();
        String token = "raw-token";
        PersistentSession session = PersistentSession.builder()
            .id(1L).seriesId(series).user(user)
            .tokenHash(PersistentSessionService.sha256Hex(token))
            .createdAt(NOW.minus(1, ChronoUnit.DAYS))
            .lastUsedAt(NOW.minus(1, ChronoUnit.HOURS))
            .expiresAt(NOW.plus(80, ChronoUnit.DAYS))
            .revokedAt(NOW.minus(30, ChronoUnit.MINUTES))
            .build();
        when(repository.findBySeriesIdForUpdate(series)).thenReturn(Optional.of(session));

        assertThat(service.validateAndRotate(series + ":" + token)).isEmpty();
        verify(repository, never()).save(any());
    }

    @Test
    void validateAndRotate_unknownSeriesReturnsEmpty() {
        UUID unknown = UUID.randomUUID();
        when(repository.findBySeriesIdForUpdate(unknown)).thenReturn(Optional.empty());
        assertThat(service.validateAndRotate(unknown + ":token")).isEmpty();
    }

    @Test
    void validateAndRotate_malformedCookieReturnsEmpty() {
        assertThat(service.validateAndRotate(null)).isEmpty();
        assertThat(service.validateAndRotate("")).isEmpty();
        assertThat(service.validateAndRotate("not-a-cookie")).isEmpty();
        assertThat(service.validateAndRotate("not-a-uuid:token")).isEmpty();
    }

    // ─── revoke ────────────────────────────────────────────────────────────

    @Test
    void revoke_setsRevokedAtIfBelongsToUser() {
        PersistentSession session = PersistentSession.builder()
            .id(1L).seriesId(UUID.randomUUID()).user(user)
            .tokenHash("h").createdAt(NOW).lastUsedAt(NOW).expiresAt(NOW.plus(1, ChronoUnit.DAYS))
            .build();
        when(repository.findById(1L)).thenReturn(Optional.of(session));

        boolean revoked = service.revoke(1L, user);

        assertThat(revoked).isTrue();
        assertThat(session.getRevokedAt()).isEqualTo(NOW);
    }

    @Test
    void revoke_rejectsIfNotOwner() {
        AppUser other = AppUser.builder().id(123L).build();
        PersistentSession session = PersistentSession.builder()
            .id(1L).seriesId(UUID.randomUUID()).user(other)
            .tokenHash("h").createdAt(NOW).lastUsedAt(NOW).expiresAt(NOW.plus(1, ChronoUnit.DAYS))
            .build();
        when(repository.findById(1L)).thenReturn(Optional.of(session));

        assertThat(service.revoke(1L, user)).isFalse();
        assertThat(session.getRevokedAt()).isNull();
    }

    @Test
    void revokeAllForUser_callsRepository() {
        when(repository.revokeAllByUserId(99L, NOW)).thenReturn(3);
        assertThat(service.revokeAllForUser(99L)).isEqualTo(3);
    }

    // ─── isSeriesActive (revocation gate for /auth/refresh) ─────────────────

    @Test
    void isSeriesActive_trueForLiveSession_falseForRevokedExpiredOrUnknown() {
        UUID live = UUID.randomUUID();
        UUID revoked = UUID.randomUUID();
        UUID expired = UUID.randomUUID();

        when(repository.findBySeriesId(live)).thenReturn(Optional.of(sessionFor(live, null, NOW.plus(10, ChronoUnit.DAYS))));
        // Revoked via "log out this device" -- revokedAt set, not yet past expiry.
        when(repository.findBySeriesId(revoked)).thenReturn(Optional.of(sessionFor(revoked, NOW.minus(1, ChronoUnit.HOURS), NOW.plus(10, ChronoUnit.DAYS))));
        // Past the 90-day cap.
        when(repository.findBySeriesId(expired)).thenReturn(Optional.of(sessionFor(expired, null, NOW.minus(1, ChronoUnit.DAYS))));

        assertThat(service.isSeriesActive(live)).isTrue();
        assertThat(service.isSeriesActive(revoked)).isFalse();
        assertThat(service.isSeriesActive(expired)).isFalse();
    }

    @Test
    void isSeriesActive_falseForUnknownSeries() {
        UUID unknown = UUID.randomUUID();
        when(repository.findBySeriesId(unknown)).thenReturn(Optional.empty());

        assertThat(service.isSeriesActive(unknown)).isFalse();
    }

    private PersistentSession sessionFor(UUID seriesId, Instant revokedAt, Instant expiresAt) {
        return PersistentSession.builder()
            .seriesId(seriesId)
            .user(user)
            .tokenHash("h")
            .createdAt(NOW.minus(1, ChronoUnit.DAYS))
            .lastUsedAt(NOW)
            .expiresAt(expiresAt)
            .revokedAt(revokedAt)
            .build();
    }

    // ─── helpers ───────────────────────────────────────────────────────────

    @Test
    void ipPrefix_handlesIpv4() {
        assertThat(PersistentSessionService.ipPrefix("192.168.1.42")).isEqualTo("192.168.1.");
    }

    @Test
    void ipPrefix_handlesIpv6() {
        assertThat(PersistentSessionService.ipPrefix("2001:db8:85a3:8d3:1319:8a2e:370:7348"))
            .isEqualTo("2001:db8:85a3:8d3");
    }

    @Test
    void ipPrefix_handlesNullAndBlank() {
        assertThat(PersistentSessionService.ipPrefix(null)).isNull();
        assertThat(PersistentSessionService.ipPrefix("")).isNull();
    }
}
