package com.picsou.controller;

import com.picsou.config.AuthCookieWriter;
import com.picsou.config.JwtUtil;
import com.picsou.config.SecureCookieProvider;
import com.picsou.config.SsoClientRegistrationRepository;
import com.picsou.config.SsoProperties;
import com.picsou.dto.ActivationRequest;
import com.picsou.dto.LoginRequest;
import com.picsou.dto.LogoutResponse;
import com.picsou.model.AppUser;
import com.picsou.model.FamilyMember;
import com.picsou.model.UserRole;
import com.picsou.repository.AppUserRepository;
import com.picsou.service.ActivationService;
import com.picsou.service.MfaService;
import com.picsou.service.PersistentSessionService;
import com.picsou.service.SessionIssuer;
import com.picsou.service.SetupAuditService;
import io.github.bucket4j.Bucket;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthControllerTest {

    @Mock AppUserRepository userRepository;
    @Mock PasswordEncoder passwordEncoder;
    @Mock JwtUtil jwtUtil;
    @Mock AuthCookieWriter cookieWriter;
    @Mock MfaService mfaService;
    @Mock PersistentSessionService persistentSessionService;
    @Mock SetupAuditService auditService;
    @Mock ObjectProvider<SsoClientRegistrationRepository> registrations;

    Map<String, Bucket> loginBuckets;
    Map<String, Bucket> mfaVerifyBuckets;
    AuthController controller;
    MockHttpServletRequest httpReq;
    MockHttpServletResponse httpRes;

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.lenient().when(jwtUtil.generateAccessToken(any(), org.mockito.ArgumentMatchers.isNull()))
            .thenAnswer(call -> jwtUtil.generateAccessToken(call.getArgument(0)));
        org.mockito.Mockito.lenient().when(jwtUtil.generateRefreshToken(any(), org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.isNull()))
            .thenAnswer(call -> jwtUtil.generateRefreshToken(call.getArgument(0)));
        loginBuckets = new HashMap<>();
        mfaVerifyBuckets = new HashMap<>();
        controller = newController(false);
        httpReq = new MockHttpServletRequest();
        httpReq.setRemoteAddr("10.0.0.5");
        httpRes = new MockHttpServletResponse();
    }

    private AuthController newController(boolean adminRecoveryEnabled) {
        return newController(adminRecoveryEnabled, true);
    }

    private AuthController newController(boolean adminRecoveryEnabled, boolean loginRateLimitEnabled) {
        return new AuthController(
            userRepository, passwordEncoder, jwtUtil,
            loginBuckets, mfaVerifyBuckets, cookieWriter,
            mfaService, persistentSessionService, new SessionIssuer(jwtUtil, cookieWriter, persistentSessionService, Clock.systemUTC()), new SsoProperties(), new ActivationService(userRepository, persistentSessionService, auditService),
            Clock.fixed(SSO_AT.plusSeconds(30), ZoneOffset.UTC), registrations, adminRecoveryEnabled,
            loginRateLimitEnabled
        );
    }

    private AppUser user(boolean activated) {
        FamilyMember member = FamilyMember.builder()
            .id(42L).displayName("Alice").build();
        return AppUser.builder()
            .id(7L).username("alice")
            .role(UserRole.ADMIN)
            .passwordHash("$2a$12$hash")
            .activated(activated)
            .tokenVersion(3L)
            .member(member)
            .build();
    }

    // ─── login ───────────────────────────────────────────────────────────

    @Test
    void login_skipsRateLimit_whenDisabledForDevelopment() {
        loginBuckets.put("10.0.0.5", Bucket.builder()
            .addLimit(io.github.bucket4j.Bandwidth.builder()
                .capacity(1)
                .refillIntervally(1, java.time.Duration.ofHours(1))
                .build())
            .build());
        Bucket exhausted = loginBuckets.get("10.0.0.5");
        exhausted.tryConsume(1);

        when(passwordEncoder.encode("login-timing-equalizer")).thenReturn("dummy-hash");
        AuthController devController = newController(false, false);
        when(userRepository.findByUsernameWithMember("alice")).thenReturn(Optional.empty());
        when(passwordEncoder.matches("pw", "dummy-hash")).thenReturn(false);

        assertThatThrownBy(() -> devController.login(
            new LoginRequest("alice", "pw", false), httpReq, httpRes))
            .isInstanceOf(BadCredentialsException.class);
        assertThat(exhausted.getAvailableTokens()).isZero();
    }

    @Test
    void login_appliesProductionRateLimitByDefault() {
        Bucket exhausted = Bucket.builder()
            .addLimit(io.github.bucket4j.Bandwidth.builder()
                .capacity(1)
                .refillIntervally(1, java.time.Duration.ofHours(1))
                .build())
            .build();
        exhausted.tryConsume(1);
        loginBuckets.put("10.0.0.5", exhausted);

        ResponseEntity<?> response = controller.login(
            new LoginRequest("alice", "pw", false), httpReq, httpRes);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void login_returns403_andSetsNoCookies_whenAccountNotActivated() {
        AppUser deactivated = user(false);
        when(userRepository.findByUsernameWithMember("alice")).thenReturn(Optional.of(deactivated));
        when(passwordEncoder.matches("pw", "$2a$12$hash")).thenReturn(true);

        ResponseEntity<?> res = controller.login(
            new LoginRequest("alice", "pw", false), httpReq, httpRes);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(res.getBody()).isInstanceOf(ProblemDetail.class);
        assertThat(((ProblemDetail) res.getBody()).getDetail()).containsIgnoringCase("not activated");
        // No session must be established for a deactivated account.
        verify(cookieWriter, never()).setAccessAndRefresh(any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
        verify(mfaService, never()).isEnabled(any());
    }

    @Test
    void login_returns403_withConsoleHint_whenRecoveryEnabled_evenWithWrongPassword() {
        AppUser deactivatedAdmin = user(false); // ADMIN, is_activated=false
        when(userRepository.findByUsernameWithMember("alice")).thenReturn(Optional.of(deactivatedAdmin));
        AuthController recoveryController = newController(true);

        ResponseEntity<?> res = recoveryController.login(
            new LoginRequest("alice", "whatever-they-typed", false), httpReq, httpRes);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        ProblemDetail body = (ProblemDetail) res.getBody();
        assertThat(body.getDetail())
            .containsIgnoringCase("console")
            .contains("ADMIN_RECOVERY_ENABLED=false");
        // Hint fires before (and regardless of) the password check — no oracle, no cookies.
        verify(passwordEncoder, never()).matches(any(), any());
        verify(cookieWriter, never()).setAccessAndRefresh(any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void login_proceeds_whenActivated_noMfa() {
        AppUser active = user(true);
        when(userRepository.findByUsernameWithMember("alice")).thenReturn(Optional.of(active));
        when(passwordEncoder.matches("pw", "$2a$12$hash")).thenReturn(true);
        when(mfaService.isEnabled(active)).thenReturn(false);
        when(jwtUtil.generateAccessToken(active)).thenReturn("acc");
        when(jwtUtil.generateRefreshToken(active)).thenReturn("ref");

        ResponseEntity<?> res = controller.login(
            new LoginRequest("alice", "pw", false), httpReq, httpRes);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(cookieWriter).setAccessAndRefresh(httpRes, "acc", "ref", false);
    }

    @Test
    void login_mfaRequired_seversLingeringSessionCookies_beforeIssuingChallenge() {
        AppUser active = user(true);
        when(userRepository.findByUsernameWithMember("alice")).thenReturn(Optional.of(active));
        when(passwordEncoder.matches("pw", "$2a$12$hash")).thenReturn(true);
        when(mfaService.isEnabled(active)).thenReturn(true);
        // A DIFFERENT identity's "Remember Me" cookie is sitting on this browser.
        httpReq.setCookies(new Cookie(AuthCookieWriter.PERSISTENT_COOKIE, "stale-admin-cookie"));
        when(persistentSessionService.isTrustedDeviceFor(active, "stale-admin-cookie")).thenReturn(false);
        when(jwtUtil.generateMfaChallengeToken(active, false)).thenReturn("chal");

        ResponseEntity<?> res = controller.login(
            new LoginRequest("alice", "pw", false), httpReq, httpRes);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).isInstanceOf(Map.class);
        assertThat(((Map<?, ?>) res.getBody()).get("mfaRequired")).isEqualTo(true);
        // The lingering session is severed and NO access/refresh is issued: the
        // caller stays unauthenticated until the second factor is verified.
        verify(cookieWriter).clearSessionCookies(httpRes);
        verify(cookieWriter).setMfaChallenge(httpRes, "chal");
        verify(cookieWriter, never()).setAccessAndRefresh(any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void login_noMfa_dropsForeignPersistentCookie_whenNotRemembering() {
        AppUser active = user(true); // id 7L
        when(userRepository.findByUsernameWithMember("alice")).thenReturn(Optional.of(active));
        when(passwordEncoder.matches("pw", "$2a$12$hash")).thenReturn(true);
        when(mfaService.isEnabled(active)).thenReturn(false);
        when(jwtUtil.generateAccessToken(active)).thenReturn("acc");
        when(jwtUtil.generateRefreshToken(active)).thenReturn("ref");
        // A "Remember Me" cookie owned by a DIFFERENT user (99) lingers on the browser.
        httpReq.setCookies(new Cookie(AuthCookieWriter.PERSISTENT_COOKIE, "foreign"));
        when(persistentSessionService.ownerUserId("foreign")).thenReturn(Optional.of(99L));

        ResponseEntity<?> res = controller.login(
            new LoginRequest("alice", "pw", false), httpReq, httpRes);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(cookieWriter).setAccessAndRefresh(httpRes, "acc", "ref", false);
        // The foreign Remember-Me cookie is cleared so it can't re-mint user 99's session.
        verify(cookieWriter).clearPersistent(httpRes);
    }

    @Test
    void login_noMfa_keepsOwnPersistentCookie_whenNotRemembering() {
        AppUser active = user(true); // id 7L
        when(userRepository.findByUsernameWithMember("alice")).thenReturn(Optional.of(active));
        when(passwordEncoder.matches("pw", "$2a$12$hash")).thenReturn(true);
        when(mfaService.isEnabled(active)).thenReturn(false);
        when(jwtUtil.generateAccessToken(active)).thenReturn("acc");
        when(jwtUtil.generateRefreshToken(active)).thenReturn("ref");
        // The lingering cookie belongs to THIS user (a trusted device not re-ticking
        // Remember Me) — it must be preserved, not cleared.
        httpReq.setCookies(new Cookie(AuthCookieWriter.PERSISTENT_COOKIE, "mine"));
        when(persistentSessionService.ownerUserId("mine")).thenReturn(Optional.of(7L));

        controller.login(new LoginRequest("alice", "pw", false), httpReq, httpRes);

        verify(cookieWriter).setAccessAndRefresh(httpRes, "acc", "ref", false);
        verify(cookieWriter, never()).clearPersistent(httpRes);
    }

    @Test
    void login_unknownUserAndWrongPassword_payIdenticalBcryptCost_soLatencyRevealsNothing() {
        // Drive the controller with a REAL bcrypt encoder (strength 12, same as prod —
        // see SecurityConfig) so the dummy-hash comparison performs genuine work, which
        // is the whole point of the timing fix. A spy lets us count the bcrypt calls and
        // inspect the hashes they ran against.
        PasswordEncoder realEncoder = spy(new BCryptPasswordEncoder(12));
        AuthController timingController = new AuthController(
            userRepository, realEncoder, jwtUtil,
            loginBuckets, mfaVerifyBuckets, cookieWriter,
            mfaService, persistentSessionService, new SessionIssuer(jwtUtil, cookieWriter, persistentSessionService, Clock.systemUTC()), new SsoProperties(), new ActivationService(userRepository, persistentSessionService, auditService),
            Clock.fixed(SSO_AT.plusSeconds(30), ZoneOffset.UTC), registrations, false, true);

        // Path A — the username does not exist: there is no stored hash to compare,
        // yet the request must still cost a full bcrypt round against the dummy hash.
        when(userRepository.findByUsernameWithMember("ghost")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> timingController.login(
                new LoginRequest("ghost", "pw", false), httpReq, httpRes))
            .isInstanceOf(BadCredentialsException.class)
            .hasMessage("Invalid credentials");

        // Path B — the username exists but the password is wrong. The stored hash is a
        // real bcrypt hash of a DIFFERENT secret, so "pw" does not match.
        AppUser known = AppUser.builder()
            .id(7L).username("alice")
            .role(UserRole.ADMIN)
            .passwordHash(realEncoder.encode("the-real-password"))
            .activated(true)
            .tokenVersion(3L)
            .member(FamilyMember.builder().id(42L).displayName("Alice").build())
            .build();
        when(userRepository.findByUsernameWithMember("alice")).thenReturn(Optional.of(known));
        assertThatThrownBy(() -> timingController.login(
                new LoginRequest("alice", "pw", false), httpReq, httpRes))
            .isInstanceOf(BadCredentialsException.class)
            .hasMessage("Invalid credentials");

        // The two failing paths are indistinguishable by wall-clock time: each ran
        // EXACTLY one bcrypt comparison, each against a real $2a$12$ hash of the SAME
        // cost factor, each fed the submitted password. Path A used the constructor's
        // dummy hash, Path B the user's stored hash — same work, no enumeration oracle.
        ArgumentCaptor<String> hashUsed = ArgumentCaptor.forClass(String.class);
        verify(realEncoder, times(2)).matches(eq("pw"), hashUsed.capture());
        assertThat(hashUsed.getAllValues())
            .hasSize(2)
            .allSatisfy(h -> assertThat(h).startsWith("$2a$12$"));

        // ...and neither failing path leaks a session.
        verify(cookieWriter, never()).setAccessAndRefresh(any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void login_pendingActivationMember_blankHash_paysOneRealBcryptRound_andReturns401() {
        // Same real-encoder spy pattern as the timing test above. A managed member
        // that has only been issued an activation link still has a BLANK password
        // hash; matches(pw, "") would return false instantly without bcrypt, leaking
        // "this profile exists, pending activation" by latency (CWE-208). The fix
        // runs the dummy-hash bcrypt round and fails like a wrong password instead.
        PasswordEncoder realEncoder = spy(new BCryptPasswordEncoder(12));
        AuthController timingController = new AuthController(
            userRepository, realEncoder, jwtUtil,
            loginBuckets, mfaVerifyBuckets, cookieWriter,
            mfaService, persistentSessionService, new SessionIssuer(jwtUtil, cookieWriter, persistentSessionService, Clock.systemUTC()), new SsoProperties(), new ActivationService(userRepository, persistentSessionService, auditService),
            Clock.fixed(SSO_AT.plusSeconds(30), ZoneOffset.UTC), registrations, false, true);

        AppUser pending = AppUser.builder()
            .id(11L).username("bob")
            .role(UserRole.MEMBER)
            .passwordHash("")          // invited but not yet activated
            .activated(false)
            .tokenVersion(0L)
            .member(FamilyMember.builder().id(50L).displayName("Bob").build())
            .build();
        when(userRepository.findByUsernameWithMember("bob")).thenReturn(Optional.of(pending));

        assertThatThrownBy(() -> timingController.login(
                new LoginRequest("bob", "pw", false), httpReq, httpRes))
            .isInstanceOf(BadCredentialsException.class)
            .hasMessage("Invalid credentials");

        // Exactly ONE real bcrypt comparison, run against the constructor's $2a$12$
        // dummy hash — same cost as the unknown-user and wrong-password paths, so a
        // pending-activation profile is indistinguishable from "no such user".
        ArgumentCaptor<String> hashUsed = ArgumentCaptor.forClass(String.class);
        verify(realEncoder, times(1)).matches(eq("pw"), hashUsed.capture());
        assertThat(hashUsed.getValue()).startsWith("$2a$12$");

        // No session is established, and a credential-less profile never reaches MFA.
        verify(cookieWriter, never()).setAccessAndRefresh(any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
        verify(mfaService, never()).isEnabled(any());
    }

    @Test
    void login_ratelimitKey_usesContainerAddress_despiteForwardedHeaders() {
        // The container resolves trusted proxies; controllers must not reinterpret headers.
        @SuppressWarnings("unchecked")
        Map<String, Bucket> mockedLoginBuckets = mock(Map.class);
        Bucket bucket = mock(Bucket.class);
        when(bucket.tryConsume(1)).thenReturn(true);
        when(mockedLoginBuckets.computeIfAbsent(any(), any())).thenReturn(bucket);
        when(userRepository.findByUsernameWithMember("alice")).thenReturn(Optional.empty());

        AuthController spoofTestController = new AuthController(
            userRepository, passwordEncoder, jwtUtil,
            mockedLoginBuckets, mfaVerifyBuckets, cookieWriter,
            mfaService, persistentSessionService, new SessionIssuer(jwtUtil, cookieWriter, persistentSessionService, Clock.systemUTC()), new SsoProperties(), new ActivationService(userRepository, persistentSessionService, auditService),
            Clock.fixed(SSO_AT.plusSeconds(30), ZoneOffset.UTC), registrations, false, true);

        MockHttpServletRequest firstCall = new MockHttpServletRequest();
        firstCall.setRemoteAddr("203.0.113.9");
        firstCall.addHeader("X-Forwarded-For", "1.1.1.1"); // attacker-supplied, rotates per request
        firstCall.addHeader("X-Real-IP", "203.0.113.99");

        MockHttpServletRequest secondCall = new MockHttpServletRequest();
        secondCall.setRemoteAddr("203.0.113.9");
        secondCall.addHeader("X-Forwarded-For", "9.9.9.9"); // rotated -- same attacker, new value
        secondCall.addHeader("X-Real-IP", "203.0.113.100");

        assertThatThrownBy(() -> spoofTestController.login(
                new LoginRequest("alice", "pw", false), firstCall, httpRes))
            .isInstanceOf(BadCredentialsException.class);
        assertThatThrownBy(() -> spoofTestController.login(
                new LoginRequest("alice", "pw", false), secondCall, httpRes))
            .isInstanceOf(BadCredentialsException.class);

        // Both calls looked up the SAME key, despite the different X-Forwarded-For each time.
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(mockedLoginBuckets, times(2)).computeIfAbsent(keyCaptor.capture(), any());
        assertThat(keyCaptor.getAllValues()).containsOnly("203.0.113.9");
    }

    // ─── activate ────────────────────────────────────────────────────────

    @Test
    void activate_bumpsTokenVersion_andRevokesPersistentSessions() {
        // M2: activate() is the shared sink for new-member activation, admin-initiated
        // password reset, and admin-recovery completion. Like change-password, it must
        // invalidate every pre-existing session so a compromised member's old JWTs and
        // Remember-Me cookies don't survive the reset (CWE-613/640).
        AppUser member = AppUser.builder()
            .id(11L).username("bob")
            .role(UserRole.MEMBER)
            .passwordHash("")
            .activated(false)
            .tokenVersion(5L)
            .activationToken("tok")
            .activationTokenExpires(Instant.now().plus(1, ChronoUnit.HOURS))
            .member(FamilyMember.builder().id(50L).displayName("Bob").build())
            .build();
        when(userRepository.findByActivationToken("tok")).thenReturn(Optional.of(member));
        when(userRepository.findByActivationTokenForUpdate("tok")).thenReturn(Optional.of(member));

        ResponseEntity<?> res = controller.activate(
            "tok", new ActivationRequest("new-password-123", true), httpReq);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(member.isActivated()).isTrue();
        // tokenVersion bumped 5 -> 6 invalidates every outstanding access/refresh JWT.
        assertThat(member.getTokenVersion()).isEqualTo(6L);
        // ...and every Remember-Me persistent session for this user is wiped.
        verify(persistentSessionService).revokeAllForUser(11L);
    }

    @Test
    void activate_refusesATokenAConcurrentActivationUsedWhileThePasswordWasHashed() {
        AppUser member = AppUser.builder()
            .id(11L).username("bob").role(UserRole.MEMBER).passwordHash("").activated(false)
            .activationToken("tok").activationTokenExpires(Instant.now().plus(1, ChronoUnit.HOURS))
            .build();
        when(userRepository.findByActivationToken("tok")).thenReturn(Optional.of(member));
        when(userRepository.findByActivationTokenForUpdate("tok")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.activate("tok", new ActivationRequest("new-password-123", true), httpReq))
            .isInstanceOf(BadCredentialsException.class);

        verify(userRepository, never()).save(any());
        verify(persistentSessionService, never()).revokeAllForUser(any());
    }

    // ─── mfa/verify ──────────────────────────────────────────────────────

    @Test
    void mfaVerify_returns400_andKeepsChallenge_whenCodeInvalid() {
        AppUser active = user(true);
        httpReq.setCookies(new Cookie(AuthCookieWriter.MFA_CHALLENGE_COOKIE, "challenge"));
        Claims claims = org.mockito.Mockito.mock(Claims.class);
        when(jwtUtil.validateAndParse("challenge")).thenReturn(claims);
        when(jwtUtil.isMfaChallengeToken(claims)).thenReturn(true);
        when(claims.get("uid", Long.class)).thenReturn(7L);
        when(userRepository.findByIdWithMember(7L)).thenReturn(Optional.of(active));
        when(mfaService.verifyTotpOrRecovery(active, "000000", false)).thenReturn(false);

        ResponseEntity<?> res = controller.mfaVerify(
            new com.picsou.dto.MfaDtos.MfaVerifyRequest("000000", false, false), httpReq, httpRes);

        // 400, not 401: the challenge is still valid, only the code is wrong. The
        // frontend keeps the user on the page to retry instead of bouncing to /login.
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(cookieWriter, never()).clearMfaChallenge(httpRes);
        verify(cookieWriter, never()).setAccessAndRefresh(any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void mfaVerify_returns401_whenNoChallengeCookie() {
        ResponseEntity<?> res = controller.mfaVerify(
            new com.picsou.dto.MfaDtos.MfaVerifyRequest("123456", false, false), httpReq, httpRes);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(mfaService, never()).verifyTotpOrRecovery(any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    // ─── refresh ─────────────────────────────────────────────────────────

    private static final String TEST_SECRET = "0123456789abcdef0123456789abcdef-test";
    private static final Instant SSO_AT = Instant.parse("2026-10-31T12:00:00Z");

    private AuthController realSessionController(JwtUtil jwt, boolean ssoEnabled) {
        SsoProperties properties = new SsoProperties();
        properties.setSessionRevalidateAfter("1m");
        if (ssoEnabled) {
            properties.setIssuerUri("http://127.0.0.1:18106/default");
            properties.setClientId("picsou");
        }
        AuthCookieWriter writer = new AuthCookieWriter(mock(SecureCookieProvider.class), jwt);
        return new AuthController(userRepository, passwordEncoder, jwt, loginBuckets, mfaVerifyBuckets,
            writer, mfaService, persistentSessionService, new SessionIssuer(jwt, writer, persistentSessionService, Clock.systemUTC()),
            properties, new ActivationService(userRepository, persistentSessionService, auditService), Clock.fixed(SSO_AT.plusSeconds(30), ZoneOffset.UTC), registrations, false, true);
    }

    private String authorizationServerToken() {
        return io.jsonwebtoken.Jwts.builder().subject("alice").claim("uid", 7L).claim("tv", 3L)
            .claim("role", "ADMIN").claim("type", "access").audience().add("picsou-ios").and()
            .issuer("http://127.0.0.1:18006").claim("sso_at", SSO_AT.getEpochSecond())
            .signWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(TEST_SECRET.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
            .compact();
    }

    @Test
    void refresh_filterRevalidation_winsOverRevokedPresentedSeries() {
        httpReq.setAttribute("picsou.sso.revalidate", Boolean.TRUE);
        httpReq.setCookies(new Cookie("persistent_token", "revoked:token"));

        ResponseEntity<?> result = controller.refresh(null, httpReq, httpRes);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(((ProblemDetail) result.getBody()).getProperties()).containsEntry("code", "SSO_REVALIDATE");
        verify(cookieWriter).clearAuthCookies(httpRes);
        org.mockito.Mockito.verifyNoInteractions(persistentSessionService);
    }

    @ParameterizedTest
    @CsvSource({"true,false", "true,true", "false,false"})
    void refresh_ssoRevalidation_precedesBoundSeriesRevocation(boolean enabled, boolean revoked) {
        JwtUtil jwt = new JwtUtil(TEST_SECRET, 15, 7, 5);
        AuthController real = realSessionController(jwt, enabled);
        AppUser active = user(true);
        UUID series = revoked ? UUID.randomUUID() : null;
        Instant marker = enabled ? SSO_AT.minusSeconds(31) : SSO_AT;
        httpReq.setCookies(new Cookie("refresh_token", jwt.generateRefreshToken(active, series, marker)));
        when(userRepository.findByIdWithMember(7L)).thenReturn(Optional.of(active));

        ResponseEntity<?> result = real.refresh(active, httpReq, httpRes);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(((ProblemDetail) result.getBody()).getProperties()).containsEntry("code", "SSO_REVALIDATE");
        assertThat(httpRes.getHeaders("Set-Cookie")).anySatisfy(header ->
            assertThat(header).startsWith("access_token=;").contains("Max-Age=0"));
        assertThat(httpRes.getHeaders("Set-Cookie")).anySatisfy(header ->
            assertThat(header).startsWith("refresh_token=;").contains("Max-Age=0"));
    }

    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> principalSessions() {
        return java.util.stream.Stream.of("cookie", "bearer").flatMap(transport ->
            java.util.stream.Stream.of(false, true).flatMap(series ->
                java.util.stream.Stream.of(false, true).flatMap(invalidRefresh ->
                    java.util.stream.Stream.of("fresh", "expired", "disabled").map(window ->
                        org.junit.jupiter.params.provider.Arguments.of(transport, series, invalidRefresh, window)))));
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource("principalSessions")
    void refresh_accessPrincipal_respectsSsoWindow_withoutUsableRefresh(String transport, boolean series,
                                                                       boolean invalidRefresh, String window) {
        JwtUtil jwt = new JwtUtil(TEST_SECRET, 15, 7, 5);
        AuthController real = realSessionController(jwt, !window.equals("disabled"));
        AppUser active = user(true);
        Instant marker = window.equals("expired") ? SSO_AT.minusSeconds(31) : SSO_AT;
        String access = jwt.generateAccessToken(active, marker);
        java.util.List<Cookie> cookies = new java.util.ArrayList<>();
        if (transport.equals("cookie")) cookies.add(new Cookie("access_token", access));
        else httpReq.addHeader("Authorization", "Bearer " + access);
        if (invalidRefresh) cookies.add(new Cookie("refresh_token", "invalid"));
        if (series) {
            UUID sid = UUID.randomUUID();
            cookies.add(new Cookie("persistent_token", "mine"));
            when(persistentSessionService.seriesFromCookie("mine")).thenReturn(Optional.of(sid));
            when(persistentSessionService.isSeriesActive(sid)).thenReturn(true);
            org.mockito.Mockito.lenient().when(persistentSessionService.ownerUserId("mine")).thenReturn(Optional.of(7L));
        }
        httpReq.setCookies(cookies.toArray(Cookie[]::new));

        ResponseEntity<?> result = real.refresh(active, httpReq, httpRes);

        if (window.equals("fresh")) {
            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(jwt.getSsoAuthenticatedAt(jwt.validateAndParse(httpRes.getCookie("access_token").getValue())))
                .isEqualTo(marker);
            assertThat(jwt.getSsoAuthenticatedAt(jwt.validateAndParse(httpRes.getCookie("refresh_token").getValue())))
                .isEqualTo(marker);
        } else {
            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(((ProblemDetail) result.getBody()).getProperties()).containsEntry("code", "SSO_REVALIDATE");
            assertThat(httpRes.getHeaders("Set-Cookie")).allSatisfy(header -> assertThat(header).contains("Max-Age=0"));
        }
    }

    @ParameterizedTest
    @CsvSource({"true,true,true,true", "false,true,true,true", "true,false,true,true", "true,true,false,true", "true,true,true,false"})
    void logout_idpUrl_requiresSsoSession_optIn_repository_andEndpoint(boolean ssoSession, boolean optIn,
                                                                     boolean available, boolean endpoint) {
        JwtUtil jwt = new JwtUtil(TEST_SECRET, 15, 7, 5);
        SsoProperties properties = new SsoProperties();
        properties.setIssuerUri("https://idp.example");
        properties.setClientId("picsou");
        properties.setIdpLogout(Boolean.toString(optIn));
        AuthCookieWriter writer = new AuthCookieWriter(mock(SecureCookieProvider.class), jwt);
        AuthController real = new AuthController(userRepository, passwordEncoder, jwt, loginBuckets, mfaVerifyBuckets,
            writer, mfaService, persistentSessionService, new SessionIssuer(jwt, writer, persistentSessionService, Clock.systemUTC()),
            properties, new ActivationService(userRepository, persistentSessionService, auditService), Clock.fixed(SSO_AT, ZoneOffset.UTC), registrations, false, true);
        httpReq.setScheme("https");
        httpReq.setServerName("picsou.example");
        httpReq.setServerPort(443);
        httpReq.setCookies(new Cookie("access_token", jwt.generateAccessToken(user(true), ssoSession ? SSO_AT : null)));
        if (ssoSession && optIn) {
            SsoClientRegistrationRepository repository = available ? mock(SsoClientRegistrationRepository.class) : null;
            when(registrations.getIfAvailable()).thenReturn(repository);
            if (available) when(repository.endSessionEndpoint()).thenReturn(endpoint
                ? Optional.of("https://idp.example/endsession") : Optional.empty());
        }

        ResponseEntity<?> result = real.logout(httpReq, httpRes);

        if (ssoSession && optIn && available && endpoint) {
            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(((LogoutResponse) result.getBody()).endSessionUrl()).isEqualTo(
                "https://idp.example/endsession?client_id=picsou&post_logout_redirect_uri=https%3A%2F%2Fpicsou.example%2Flogin");
        } else assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(httpRes.getHeaders("Set-Cookie")).hasSize(5)
            .allSatisfy(header -> assertThat(header).contains("Max-Age=0"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"refresh", "cookie", "bearer", "series"})
    void refresh_preservesSsoAt_inEverySessionSource(String source) {
        JwtUtil jwt = new JwtUtil(TEST_SECRET, 15, 7, 5);
        AuthController real = realSessionController(jwt, true);
        AppUser active = user(true);
        switch (source) {
            case "refresh" -> {
                httpReq.setCookies(new Cookie("refresh_token", jwt.generateRefreshToken(active, null, SSO_AT)));
                when(userRepository.findByIdWithMember(7L)).thenReturn(Optional.of(active));
            }
            case "cookie" -> httpReq.setCookies(new Cookie("access_token", jwt.generateAccessToken(active, SSO_AT)));
            case "bearer" -> httpReq.addHeader("Authorization", "Bearer " + jwt.generateAccessToken(active, SSO_AT));
            case "series" -> {
                UUID series = UUID.randomUUID();
                httpReq.setCookies(new Cookie("persistent_token", "mine"));
                when(persistentSessionService.seriesFromCookie("mine")).thenReturn(Optional.of(series));
                when(persistentSessionService.isSeriesActive(series)).thenReturn(true);
                when(persistentSessionService.ownerUserId("mine")).thenReturn(Optional.of(7L));
                when(persistentSessionService.ssoAuthenticatedAtOf(series)).thenReturn(Optional.of(SSO_AT));
            }
            default -> throw new IllegalArgumentException(source);
        }

        ResponseEntity<?> result = real.refresh(source.equals("refresh") ? null : active, httpReq, httpRes);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(jwt.getSsoAuthenticatedAt(jwt.validateAndParse(httpRes.getCookie("refresh_token").getValue())))
            .isEqualTo(SSO_AT);
        assertThat(jwt.getSsoAuthenticatedAt(jwt.validateAndParse(httpRes.getCookie("access_token").getValue())))
            .isEqualTo(SSO_AT);
    }

    @Test
    void logout_freshRepository_discoversEndSessionEndpointLazily() throws Exception {
        com.sun.net.httpserver.HttpServer idp = com.sun.net.httpserver.HttpServer.create(
            new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        String issuer = "http://127.0.0.1:" + idp.getAddress().getPort();
        byte[] discovery = ("{\"issuer\":\"" + issuer + "\",\"authorization_endpoint\":\"" + issuer
            + "/authorize\",\"token_endpoint\":\"" + issuer + "/token\",\"jwks_uri\":\"" + issuer
            + "/jwks\",\"response_types_supported\":[\"code\"],\"subject_types_supported\":[\"public\"],"
            + "\"id_token_signing_alg_values_supported\":[\"RS256\"],\"end_session_endpoint\":\"" + issuer
            + "/endsession\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        idp.createContext("/.well-known/openid-configuration", exchange -> {
            try (exchange) {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, discovery.length);
                exchange.getResponseBody().write(discovery);
            }
        });
        idp.start();
        try {
            SsoProperties properties = new SsoProperties();
            properties.setIssuerUri(issuer);
            properties.setClientId("picsou");
            properties.setIdpLogout("true");
            Clock clock = Clock.fixed(SSO_AT, ZoneOffset.UTC);
            when(registrations.getIfAvailable()).thenReturn(new SsoClientRegistrationRepository(properties, clock));
            JwtUtil jwt = new JwtUtil(TEST_SECRET, 15, 7, 5);
            AuthCookieWriter writer = new AuthCookieWriter(mock(SecureCookieProvider.class), jwt);
            AuthController real = new AuthController(userRepository, passwordEncoder, jwt, loginBuckets, mfaVerifyBuckets,
                writer, mfaService, persistentSessionService, new SessionIssuer(jwt, writer, persistentSessionService, clock),
                properties, new ActivationService(userRepository, persistentSessionService, auditService), clock, registrations, false, true);
            httpReq.setCookies(new Cookie("refresh_token", jwt.generateRefreshToken(user(true), null, SSO_AT)));

            ResponseEntity<?> result = real.logout(httpReq, httpRes);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(((LogoutResponse) result.getBody()).endSessionUrl()).isEqualTo(issuer
                + "/endsession?client_id=picsou&post_logout_redirect_uri=http%3A%2F%2Flocalhost%2Flogin");
        } finally {
            idp.stop(0);
        }
    }

    @ParameterizedTest
    @CsvSource({"cookie,true", "bearer,true", "cookie,false", "bearer,false"})
    void refresh_authorizationServerToken_mintsWebSessionOnlyWhenSsoDisabled(String transport, boolean ssoEnabled) {
        JwtUtil jwt = new JwtUtil(TEST_SECRET, 15, 7, 5);
        AuthController real = realSessionController(jwt, ssoEnabled);
        if (transport.equals("cookie")) httpReq.setCookies(new Cookie("access_token", authorizationServerToken()));
        else httpReq.addHeader("Authorization", "Bearer " + authorizationServerToken());

        ResponseEntity<?> result = real.refresh(user(true), httpReq, httpRes);

        assertThat(result.getStatusCode()).isEqualTo(ssoEnabled ? HttpStatus.UNAUTHORIZED : HttpStatus.OK);
        if (ssoEnabled) {
            assertThat(((ProblemDetail) result.getBody()).getDetail()).isEqualTo("No refresh token");
            assertThat(httpRes.getCookie("access_token").getValue()).isEmpty();
            assertThat(httpRes.getCookie("refresh_token").getValue()).isEmpty();
        } else {
            assertThat(jwt.isAccessToken(jwt.validateAndParse(httpRes.getCookie("access_token").getValue()))).isTrue();
            assertThat(jwt.isRefreshToken(jwt.validateAndParse(httpRes.getCookie("refresh_token").getValue()))).isTrue();
        }
    }

    @ParameterizedTest
    @CsvSource({"cookie,true", "bearer,true", "cookie,false", "bearer,false"})
    void changeUsername_authorizationServerToken_mintsCookiesOnlyWhenSsoDisabled(String transport, boolean ssoEnabled) {
        JwtUtil jwt = new JwtUtil(TEST_SECRET, 15, 7, 5);
        AuthController real = realSessionController(jwt, ssoEnabled);
        AppUser active = user(true);
        if (transport.equals("cookie")) httpReq.setCookies(new Cookie("access_token", authorizationServerToken()));
        else httpReq.addHeader("Authorization", "Bearer " + authorizationServerToken());

        ResponseEntity<?> result = real.changeUsername(active, new AuthController.ChangeUsernameRequest("renamed"), httpReq, httpRes);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(userRepository).save(active);
        assertThat(active.getUsername()).isEqualTo("renamed");
        if (ssoEnabled) assertThat(httpRes.getHeaders("Set-Cookie")).isEmpty();
        else {
            assertThat(jwt.validateAndParse(httpRes.getCookie("access_token").getValue()).getSubject()).isEqualTo("renamed");
            assertThat(jwt.validateAndParse(httpRes.getCookie("refresh_token").getValue()).getSubject()).isEqualTo("renamed");
        }
    }

    @Test
    void changeUsername_preservesSsoAt_fromWebSession() {
        JwtUtil jwt = new JwtUtil(TEST_SECRET, 15, 7, 5);
        AuthController real = realSessionController(jwt, true);
        AppUser active = user(true);
        httpReq.setCookies(new Cookie("refresh_token", jwt.generateRefreshToken(active, null, SSO_AT)));

        real.changeUsername(active, new AuthController.ChangeUsernameRequest("renamed"), httpReq, httpRes);

        assertThat(jwt.getSsoAuthenticatedAt(jwt.validateAndParse(httpRes.getCookie("access_token").getValue()))).isEqualTo(SSO_AT);
        assertThat(jwt.getSsoAuthenticatedAt(jwt.validateAndParse(httpRes.getCookie("refresh_token").getValue()))).isEqualTo(SSO_AT);
    }

    @Test
    void changePassword_issuesPasswordSession_withoutSsoAt() {
        JwtUtil jwt = new JwtUtil(TEST_SECRET, 15, 7, 5);
        AuthController real = realSessionController(jwt, true);
        AppUser active = user(true);
        httpReq.setCookies(new Cookie("refresh_token", jwt.generateRefreshToken(active, null, SSO_AT)));
        when(passwordEncoder.matches("old", active.getPasswordHash())).thenReturn(true);

        real.changePassword(active, new AuthController.ChangePasswordRequest("old", "new-password"), httpReq, httpRes);

        assertThat(jwt.validateAndParse(httpRes.getCookie("access_token").getValue())).doesNotContainKey("sso_at");
        assertThat(jwt.validateAndParse(httpRes.getCookie("refresh_token").getValue())).doesNotContainKey("sso_at");
    }

    @Test
    void refresh_returns401_andClearsCookies_whenAccountNotActivated() {
        AppUser deactivated = user(false);
        httpReq.setCookies(new Cookie("refresh_token", "rt"));
        Claims claims = org.mockito.Mockito.mock(Claims.class);
        when(jwtUtil.validateAndParse("rt")).thenReturn(claims);
        when(jwtUtil.isRefreshToken(claims)).thenReturn(true);
        when(jwtUtil.getUserId(claims)).thenReturn(7L);
        when(userRepository.findByIdWithMember(7L)).thenReturn(Optional.of(deactivated));
        when(jwtUtil.getTokenVersion(claims)).thenReturn(3L); // matches user.tokenVersion

        ResponseEntity<?> res = controller.refresh(null, httpReq, httpRes);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(cookieWriter).clearAuthCookies(httpRes);
        verify(jwtUtil, never()).generateAccessToken(any());
    }

    @Test
    void refresh_rotatesTokens_asSessionCookies_whenActivated_andTvMatches_andNoRememberMe() {
        AppUser active = user(true);
        httpReq.setCookies(new Cookie("refresh_token", "rt"));
        Claims claims = org.mockito.Mockito.mock(Claims.class);
        when(jwtUtil.validateAndParse("rt")).thenReturn(claims);
        when(jwtUtil.isRefreshToken(claims)).thenReturn(true);
        when(jwtUtil.getUserId(claims)).thenReturn(7L);
        when(userRepository.findByIdWithMember(7L)).thenReturn(Optional.of(active));
        when(jwtUtil.getTokenVersion(claims)).thenReturn(3L);
        when(jwtUtil.generateAccessToken(active)).thenReturn("acc2");
        when(jwtUtil.generateRefreshToken(active)).thenReturn("ref2");

        ResponseEntity<?> res = controller.refresh(null, httpReq, httpRes);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        // No persistent_token cookie on this request -> rotated as session cookies, so a
        // non-"Remember Me" login can't be silently resurrected past the browser closing.
        verify(cookieWriter).setAccessAndRefresh(httpRes, "acc2", "ref2", false);
    }

    /**
     * The token names its account by the immutable uid, never by the username in `sub`: a
     * username can be changed (change-username) or freed and given to a new member, and
     * resolving by name logged the old token's holder into whoever carried that name next,
     * or locked a renamed user out at the next refresh.
     */
    @Test
    void refresh_resolvesTheAccountByUid_neverByTheUsernameInTheSubject() {
        AppUser alice = user(true);
        httpReq.setCookies(new Cookie("refresh_token", "rt"));
        Claims claims = org.mockito.Mockito.mock(Claims.class);
        when(jwtUtil.validateAndParse("rt")).thenReturn(claims);
        when(jwtUtil.isRefreshToken(claims)).thenReturn(true);
        when(jwtUtil.getUserId(claims)).thenReturn(7L);
        when(userRepository.findByIdWithMember(7L)).thenReturn(Optional.of(alice));
        when(jwtUtil.getTokenVersion(claims)).thenReturn(3L);
        when(jwtUtil.generateAccessToken(alice)).thenReturn("acc2");
        when(jwtUtil.generateRefreshToken(alice)).thenReturn("ref2");

        ResponseEntity<?> res = controller.refresh(null, httpReq, httpRes);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(cookieWriter).setAccessAndRefresh(httpRes, "acc2", "ref2", false);
        verify(userRepository, never()).findByUsernameWithMember(any());
        verify(claims, never()).getSubject();
    }

    @Test
    void refresh_returns401_whenTheTokenCarriesNoUid() {
        httpReq.setCookies(new Cookie("refresh_token", "rt"));
        Claims claims = org.mockito.Mockito.mock(Claims.class);
        when(jwtUtil.validateAndParse("rt")).thenReturn(claims);
        when(jwtUtil.isRefreshToken(claims)).thenReturn(true);
        when(jwtUtil.getUserId(claims)).thenReturn(null);

        ResponseEntity<?> res = controller.refresh(null, httpReq, httpRes);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(userRepository, never()).findByIdWithMember(any());
        verify(userRepository, never()).findByUsernameWithMember(any());
    }

    @Test
    void refresh_rotatesTokens_asPersistentCookies_whenPersistentTokenCookiePresentAndOwned() {
        AppUser active = user(true); // id 7L
        httpReq.setCookies(
            new Cookie("refresh_token", "rt"),
            new Cookie(AuthCookieWriter.PERSISTENT_COOKIE, "mine"));
        Claims claims = org.mockito.Mockito.mock(Claims.class);
        when(jwtUtil.validateAndParse("rt")).thenReturn(claims);
        when(jwtUtil.isRefreshToken(claims)).thenReturn(true);
        when(jwtUtil.getUserId(claims)).thenReturn(7L);
        when(userRepository.findByIdWithMember(7L)).thenReturn(Optional.of(active));
        when(jwtUtil.getTokenVersion(claims)).thenReturn(3L);
        when(jwtUtil.generateAccessToken(active)).thenReturn("acc2");
        when(jwtUtil.generateRefreshToken(active)).thenReturn("ref2");
        when(persistentSessionService.ownerUserId("mine")).thenReturn(Optional.of(7L));

        ResponseEntity<?> res = controller.refresh(null, httpReq, httpRes);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(cookieWriter).setAccessAndRefresh(httpRes, "acc2", "ref2", true);
    }

    @Test
    void refresh_returns200_andMintsFreshCookies_fromPersistentPrincipal_whenNoRefreshTokenCookie() {
        // No refresh_token cookie in the request, but PersistentTokenAuthFilter already
        // re-authenticated this request from a valid persistent_token one filter earlier.
        AppUser active = user(true);
        httpReq.setCookies(new Cookie(AuthCookieWriter.PERSISTENT_COOKIE, "mine"));
        when(persistentSessionService.ownerUserId("mine")).thenReturn(Optional.of(active.getId()));
        when(jwtUtil.generateAccessToken(active)).thenReturn("acc3");
        when(jwtUtil.generateRefreshToken(active)).thenReturn("ref3");

        ResponseEntity<?> res = controller.refresh(active, httpReq, httpRes);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).isEqualTo(Map.of(
            "username", active.getUsername(),
            "role", active.getRole().name(),
            "memberId", active.getMember().getId(),
            "displayName", active.getMember().getDisplayName()
        ));
        // The endpoint's contract is "200 = fresh cookies were issued" -- always mint here,
        // regardless of whether PersistentTokenAuthFilter already wrote a pair moments ago,
        // so a bare access-token-derived principal (no persistent_token at all) never gets
        // a phantom 200 with zero Set-Cookie that dies within the access token's 15 minutes.
        verify(cookieWriter).setAccessAndRefresh(httpRes, "acc3", "ref3", true);
    }

    @Test
    void refresh_returns401_whenNoRefreshTokenCookie_andNoPersistentPrincipal() {
        ResponseEntity<?> res = controller.refresh(null, httpReq, httpRes);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(cookieWriter).clearAuthCookies(httpRes);
    }

    @Test
    void refresh_honoursPersistentPrincipal_whenRefreshTokenPresentButTokenVersionRevoked() {
        // Concrete trigger from the review: something (e.g. AdminRecoveryRunner) bumps
        // tokenVersion without revoking persistent sessions. The stale refresh_token cookie
        // is now invalid, but a still-valid persistent_token was ALSO presented on this same
        // request and PersistentTokenAuthFilter already re-authenticated it -- that must win
        // instead of a flat 401, and cookies must not be cleared out from under the filter's
        // just-rotated persistent_token.
        AppUser active = user(true);
        httpReq.setCookies(
            new Cookie("refresh_token", "stale-rt"),
            new Cookie(AuthCookieWriter.PERSISTENT_COOKIE, "mine"));
        Claims claims = org.mockito.Mockito.mock(Claims.class);
        when(jwtUtil.validateAndParse("stale-rt")).thenReturn(claims);
        when(jwtUtil.isRefreshToken(claims)).thenReturn(true);
        when(jwtUtil.getUserId(claims)).thenReturn(7L);
        when(userRepository.findByIdWithMember(7L)).thenReturn(Optional.of(active));
        when(jwtUtil.getTokenVersion(claims)).thenReturn(1L); // stale -- user is now at tv=3
        when(persistentSessionService.ownerUserId("mine")).thenReturn(Optional.of(active.getId()));
        when(jwtUtil.generateAccessToken(active)).thenReturn("acc4");
        when(jwtUtil.generateRefreshToken(active)).thenReturn("ref4");

        ResponseEntity<?> res = controller.refresh(active, httpReq, httpRes);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(cookieWriter).setAccessAndRefresh(httpRes, "acc4", "ref4", true);
        verify(cookieWriter, never()).clearAuthCookies(any());
    }

    @Test
    void refresh_returns401_andClears_whenRefreshTokenBoundToRevokedSeries() {
        // "Log out this device": the persistent session was revoked, but the device still
        // holds a cryptographically-valid, series-bound refresh_token. It must be cut -- and
        // must NOT fall through to a re-mint from the still-valid access-token principal.
        AppUser active = user(true);
        UUID revokedSeries = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        httpReq.setCookies(new Cookie("refresh_token", "rt"));
        Claims claims = org.mockito.Mockito.mock(Claims.class);
        when(jwtUtil.validateAndParse("rt")).thenReturn(claims);
        when(jwtUtil.isRefreshToken(claims)).thenReturn(true);
        when(jwtUtil.getUserId(claims)).thenReturn(7L);
        when(userRepository.findByIdWithMember(7L)).thenReturn(Optional.of(active));
        when(jwtUtil.getTokenVersion(claims)).thenReturn(3L);
        when(jwtUtil.getSeriesId(claims)).thenReturn(revokedSeries);
        when(persistentSessionService.isSeriesActive(revokedSeries)).thenReturn(false);

        ResponseEntity<?> res = controller.refresh(active, httpReq, httpRes);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(cookieWriter).clearAuthCookies(httpRes);
        verify(jwtUtil, never()).generateAccessToken(any());
    }

    @Test
    void refresh_returns401_whenPersistentCookieSeriesRevoked_evenWithValidAccessPrincipal() {
        // The device presents a persistent_token whose series was revoked ("log out
        // everywhere else"). Even a still-valid access-token principal on the same request
        // must not re-establish the session -- the front guard fires before any minting.
        AppUser active = user(true);
        UUID revokedSeries = UUID.fromString("dddddddd-eeee-ffff-0000-111111111111");
        httpReq.setCookies(new Cookie(AuthCookieWriter.PERSISTENT_COOKIE, "revoked:tok"));
        when(persistentSessionService.seriesFromCookie("revoked:tok")).thenReturn(Optional.of(revokedSeries));
        when(persistentSessionService.isSeriesActive(revokedSeries)).thenReturn(false);

        ResponseEntity<?> res = controller.refresh(active, httpReq, httpRes);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(cookieWriter).clearAuthCookies(httpRes);
        verify(jwtUtil, never()).generateAccessToken(any());
    }
}
