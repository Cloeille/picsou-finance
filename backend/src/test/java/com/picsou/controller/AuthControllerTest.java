package com.picsou.controller;

import com.picsou.config.AuthCookieWriter;
import com.picsou.config.JwtUtil;
import com.picsou.dto.ActivationRequest;
import com.picsou.dto.LoginRequest;
import com.picsou.model.AppUser;
import com.picsou.model.FamilyMember;
import com.picsou.model.UserRole;
import com.picsou.repository.AppUserRepository;
import com.picsou.service.MfaService;
import com.picsou.service.PersistentSessionService;
import com.picsou.service.SetupAuditService;
import io.github.bucket4j.Bucket;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import jakarta.servlet.http.Cookie;

import java.time.Instant;
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

    Map<String, Bucket> loginBuckets;
    Map<String, Bucket> mfaVerifyBuckets;
    AuthController controller;
    MockHttpServletRequest httpReq;
    MockHttpServletResponse httpRes;

    @BeforeEach
    void setUp() {
        loginBuckets = new HashMap<>();
        mfaVerifyBuckets = new HashMap<>();
        controller = newController(false);
        httpReq = new MockHttpServletRequest();
        httpReq.setRemoteAddr("10.0.0.5");
        httpRes = new MockHttpServletResponse();
    }

    private AuthController newController(boolean adminRecoveryEnabled) {
        return new AuthController(
            userRepository, passwordEncoder, jwtUtil,
            loginBuckets, mfaVerifyBuckets, cookieWriter,
            mfaService, persistentSessionService, auditService,
            adminRecoveryEnabled
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
            mfaService, persistentSessionService, auditService, false);

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
            mfaService, persistentSessionService, auditService, false);

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
    void login_ratelimitKey_isXRealIp_notSpoofableXForwardedFor() {
        // Regression for the leftmost-XFF spoofing bug: request.getRemoteAddr() is tainted by
        // ForwardedHeaderFilter from whatever the client puts in X-Forwarded-For (nginx only
        // appends, never replaces), so a raw client could rotate it and get a fresh bucket every
        // request. ClientIp.resolve() must key on nginx's X-Real-IP instead, which stays constant
        // for the same real client regardless of the spoofed XFF value.
        @SuppressWarnings("unchecked")
        Map<String, Bucket> mockedLoginBuckets = mock(Map.class);
        Bucket bucket = mock(Bucket.class);
        when(bucket.tryConsume(1)).thenReturn(true);
        when(mockedLoginBuckets.computeIfAbsent(any(), any())).thenReturn(bucket);
        when(userRepository.findByUsernameWithMember("alice")).thenReturn(Optional.empty());

        AuthController spoofTestController = new AuthController(
            userRepository, passwordEncoder, jwtUtil,
            mockedLoginBuckets, mfaVerifyBuckets, cookieWriter,
            mfaService, persistentSessionService, auditService, false);

        MockHttpServletRequest firstCall = new MockHttpServletRequest();
        firstCall.setRemoteAddr("172.18.0.2");
        firstCall.addHeader("X-Forwarded-For", "1.1.1.1"); // attacker-supplied, rotates per request
        firstCall.addHeader("X-Real-IP", "203.0.113.9");   // nginx-observed peer, stable

        MockHttpServletRequest secondCall = new MockHttpServletRequest();
        secondCall.setRemoteAddr("172.18.0.2");
        secondCall.addHeader("X-Forwarded-For", "9.9.9.9"); // rotated -- same attacker, new value
        secondCall.addHeader("X-Real-IP", "203.0.113.9");   // unchanged: same real client

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

        ResponseEntity<?> res = controller.activate(
            "tok", new ActivationRequest("new-password-123", true), httpReq);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(member.isActivated()).isTrue();
        // tokenVersion bumped 5 -> 6 invalidates every outstanding access/refresh JWT.
        assertThat(member.getTokenVersion()).isEqualTo(6L);
        // ...and every Remember-Me persistent session for this user is wiped.
        verify(persistentSessionService).revokeAllForUser(11L);
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
