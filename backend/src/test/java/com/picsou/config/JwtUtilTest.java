package com.picsou.config;

import com.picsou.model.AppUser;
import com.picsou.model.UserRole;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.time.Instant;
import io.jsonwebtoken.Jwts;

import static org.assertj.core.api.Assertions.assertThat;

class JwtUtilTest {

    private JwtUtil jwt;
    private AppUser user;

    @BeforeEach
    void setUp() {
        // 32+ char secret to satisfy the HS256 length check.
        jwt = new JwtUtil("0123456789abcdef0123456789abcdef-test", 15, 7, 5);
        user = AppUser.builder()
            .id(42L)
            .username("alice")
            .passwordHash("h")
            .role(UserRole.ADMIN)
            .build();
    }

    // ─── token type isolation ─────────────────────────────────────────────

    @Test
    void ssoAt_roundTripsOnAccessAndRefresh_withoutChangingPasswordTokens() {
        Instant authenticatedAt = Instant.parse("2026-10-31T12:34:56Z");
        UUID series = UUID.fromString("11111111-2222-3333-4444-555555555555");

        Claims access = jwt.validateAndParse(jwt.generateAccessToken(user, authenticatedAt));
        Claims refresh = jwt.validateAndParse(jwt.generateRefreshToken(user, series, authenticatedAt));

        assertThat(jwt.getSsoAuthenticatedAt(access)).isEqualTo(authenticatedAt);
        assertThat(jwt.getSsoAuthenticatedAt(refresh)).isEqualTo(authenticatedAt);
        assertThat(refresh.get("sso_at", Number.class).longValue()).isEqualTo(authenticatedAt.getEpochSecond());
        assertThat(jwt.getSeriesId(refresh)).isEqualTo(series);
        assertThat(jwt.validateAndParse(jwt.generateAccessToken(user))).doesNotContainKey("sso_at");
        assertThat(jwt.validateAndParse(jwt.generateRefreshToken(user))).doesNotContainKey("sso_at");
        assertThat(jwt.validateAndParse(jwt.generateRefreshToken(user, series))).doesNotContainKey("sso_at");
    }

    @Test
    void ssoAt_isNullForAbsentOrNonNumericClaims() {
        assertThat(jwt.getSsoAuthenticatedAt(Jwts.claims().build())).isNull();
        assertThat(jwt.getSsoAuthenticatedAt(Jwts.claims().add("sso_at", "yesterday").build())).isNull();
    }

    @Test
    void accessToken_isOnlyRecognizedAsAccess() {
        Claims claims = jwt.validateAndParse(jwt.generateAccessToken(user));
        assertThat(jwt.isAccessToken(claims)).isTrue();
        assertThat(jwt.isRefreshToken(claims)).isFalse();
        assertThat(jwt.isMfaChallengeToken(claims)).isFalse();
    }

    @Test
    void refreshToken_isOnlyRecognizedAsRefresh() {
        Claims claims = jwt.validateAndParse(jwt.generateRefreshToken(user));
        assertThat(jwt.isRefreshToken(claims)).isTrue();
        assertThat(jwt.isAccessToken(claims)).isFalse();
        assertThat(jwt.isMfaChallengeToken(claims)).isFalse();
    }

    @Test
    void refreshToken_carriesSeriesId_whenBoundToPersistentSession_andNoneOtherwise() {
        UUID series = UUID.fromString("11111111-2222-3333-4444-555555555555");

        // A "Remember Me" refresh token round-trips its series binding (sid claim) so
        // /auth/refresh can later check whether that persistent session is still active.
        Claims bound = jwt.validateAndParse(jwt.generateRefreshToken(user, series));
        assertThat(jwt.getSeriesId(bound)).isEqualTo(series);

        // The bare overload (non-"Remember Me") carries no binding.
        Claims unbound = jwt.validateAndParse(jwt.generateRefreshToken(user));
        assertThat(jwt.getSeriesId(unbound)).isNull();

        // Access tokens are never series-bound (kept sid-free so validation stays a pure
        // signature/tokenVersion check with no per-request session lookup).
        Claims access = jwt.validateAndParse(jwt.generateAccessToken(user));
        assertThat(jwt.getSeriesId(access)).isNull();
    }

    @Test
    void mfaChallengeToken_isOnlyRecognizedAsMfaChallenge() {
        Claims claims = jwt.validateAndParse(jwt.generateMfaChallengeToken(user, false));
        assertThat(jwt.isMfaChallengeToken(claims)).isTrue();
        assertThat(jwt.isAccessToken(claims)).isFalse();
        assertThat(jwt.isRefreshToken(claims)).isFalse();
    }

    // ─── mfa_challenge specifics ──────────────────────────────────────────

    @Test
    void mfaChallengeToken_carriesUserIdAndRole() {
        Claims claims = jwt.validateAndParse(jwt.generateMfaChallengeToken(user, true));
        assertThat(claims.getSubject()).isEqualTo("alice");
        assertThat(claims.get("uid", Long.class)).isEqualTo(42L);
        assertThat(claims.get("role", String.class)).isEqualTo("ADMIN");
    }

    @Test
    void mfaChallengeToken_persistsRememberMeFlag() {
        Claims yes = jwt.validateAndParse(jwt.generateMfaChallengeToken(user, true));
        Claims no  = jwt.validateAndParse(jwt.generateMfaChallengeToken(user, false));

        assertThat(jwt.getRememberMeClaim(yes)).isTrue();
        assertThat(jwt.getRememberMeClaim(no)).isFalse();
    }

    @Test
    void rememberMeClaim_isFalseOnTokensWithoutTheClaim() {
        // Access/refresh tokens never set remember_me — accessor must not blow up
        // and must return false (defensive default).
        Claims access = jwt.validateAndParse(jwt.generateAccessToken(user));
        assertThat(jwt.getRememberMeClaim(access)).isFalse();
    }

    // ─── expiry exposure ──────────────────────────────────────────────────

    @Test
    void mfaChallengeExpirySeconds_matchesConstructor() {
        // 5 minutes → 300 seconds.
        assertThat(jwt.getMfaChallengeExpirySeconds()).isEqualTo(300L);
    }
}
