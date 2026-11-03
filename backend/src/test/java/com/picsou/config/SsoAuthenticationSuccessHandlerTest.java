package com.picsou.config;

import com.picsou.model.AppUser;
import com.picsou.model.UserRole;
import com.picsou.service.SessionIssuer;
import com.picsou.service.ReAuthService;
import com.picsou.service.SetupAuditService;
import com.picsou.service.SetupService;
import jakarta.servlet.http.Cookie;
import java.util.HashMap;
import com.picsou.service.sso.SsoAccountService;
import com.picsou.service.sso.SsoFlowException;
import com.picsou.service.sso.SsoIdentity;
import com.picsou.service.sso.SsoIntent;
import com.picsou.service.sso.SsoRoleSyncService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
// allow: SIZE_OK - The plan's targeted class owns the complete SSO callback security matrix.
class SsoAuthenticationSuccessHandlerTest {

    private static final Instant NOW = Instant.parse("2026-11-03T10:00:00Z");

    @Mock private SsoAccountService ssoAccountService;
    @Mock private SsoRoleSyncService ssoRoleSyncService;
    @Mock private SessionIssuer sessionIssuer;
    @Mock private SsoProperties ssoProperties;
    @Mock private JwtTokenAuthenticator authenticator;
    @Mock private ReAuthService reAuthService;
    @Mock private JwtUtil jwtUtil;
    @Mock private AuthCookieWriter cookieWriter;
    @Mock private SetupAuditService auditService;
    @Mock private SetupService setupService;

    @BeforeEach
    void stubGroupsClaim() {
        SecurityContextHolder.clearContext();
        lenient().when(ssoProperties.groupsClaim()).thenReturn("groups");
    }

    private SsoAuthenticationSuccessHandler handler(boolean adminRecoveryEnabled) {
        return new SsoAuthenticationSuccessHandler(ssoAccountService, ssoRoleSyncService, sessionIssuer,
            ssoProperties, Clock.fixed(NOW, ZoneOffset.UTC), adminRecoveryEnabled,
            authenticator, reAuthService, jwtUtil, cookieWriter, auditService, setupService);
    }

    private static MockHttpServletRequest requestWith(SsoIntent intent) {
        var request = new MockHttpServletRequest();
        request.setSession(new MockHttpSession());
        if (intent != null) {
            var authorization = OAuth2AuthorizationRequest.authorizationCode()
                .authorizationUri("https://idp.test/authorize").clientId("picsou")
                .attributes(a -> a.put(SsoIntent.ATTRIBUTE, intent)).build();
            request.setAttribute(SsoAuthorizationRequestRepository.REMOVED_REQUEST_ATTRIBUTE, authorization);
        }
        return request;
    }

    private static SsoIntent loginIntent(String redirect, boolean rememberMe) {
        return new SsoIntent(SsoIntent.Kind.LOGIN, redirect, rememberMe, null, null, Instant.EPOCH);
    }

    private static Authentication aliceAuthentication() {
        Map<String, Object> claims = Map.of(
            "sub", "alice",
            "iss", "https://idp.test",
            "email", "alice@idp.test",
            "name", "Alice",
            "preferred_username", "alice@idp.test",
            "groups", List.of("picsou-admin"));
        OidcIdToken idToken = new OidcIdToken("id-token", Instant.EPOCH, Instant.EPOCH.plusSeconds(300), claims);
        OidcUser principal = new DefaultOidcUser(List.of(), idToken, "sub");
        return new UsernamePasswordAuthenticationToken(principal, null, List.of());
    }

    private static AppUser user(long id, String username, UserRole role, boolean activated) {
        return AppUser.builder().id(id).username(username).passwordHash("x").role(role).activated(activated).build();
    }

    @Test
    void setupWithoutAdminGroupMembershipDiscardsHandshakeAndNeverSeeds() throws Exception {
        SsoIntent intent = new SsoIntent(SsoIntent.Kind.SETUP, "/setup/admin", false, null, null, Instant.EPOCH);
        var request = requestWith(intent);
        var session = (MockHttpSession) request.getSession(false);
        var response = new MockHttpServletResponse();
        when(ssoProperties.adminGroup()).thenReturn("different-admin-group");
        handler(false).onAuthenticationSuccess(request, response, aliceAuthentication());
        assertThat(response.getRedirectedUrl()).isEqualTo("/setup/admin?ssoError=NOT_IN_ADMIN_GROUP");
        assertThat(session.isInvalid()).isTrue();
        assertThat(response.getHeaders("Set-Cookie")).isEmpty();
        verifyNoInteractions(ssoAccountService, setupService, sessionIssuer, auditService);
    }

    @Test
    void setupSeedsAndAuditsBeforeIssuingNonPersistentSessionAndOpeningSecurityStep() throws Exception {
        var request = requestWith(new SsoIntent(SsoIntent.Kind.SETUP, "/setup/admin", true, null, null, NOW));
        var session = (MockHttpSession) request.getSession(false);
        var response = new MockHttpServletResponse();
        var alice = user(1L, "alice", UserRole.ADMIN, true);
        when(ssoProperties.adminGroup()).thenReturn("picsou-admin");
        when(ssoAccountService.usernameFor(any())).thenReturn("alice");
        when(ssoAccountService.displayNameFor(any())).thenReturn("Alice");
        when(setupService.seedSsoAdmin(any(), eq("alice"), eq("Alice"))).thenReturn(alice);

        handler(false).onAuthenticationSuccess(request, response, aliceAuthentication());

        var identity = ArgumentCaptor.forClass(SsoIdentity.class);
        InOrder order = inOrder(setupService, auditService, sessionIssuer);
        order.verify(setupService).seedSsoAdmin(identity.capture(), eq("alice"), eq("Alice"));
        assertThat(identity.getValue().subject()).isEqualTo("alice");
        order.verify(auditService).record("setup.admin.created", "alice", request, "method=sso");
        order.verify(sessionIssuer).issue(alice, false, false, NOW, request, response);
        assertThat(response.getRedirectedUrl()).isEqualTo("/setup/security");
        assertThat(session.isInvalid()).isTrue();
        verifyNoInteractions(ssoRoleSyncService);
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource("bootstrapFailures")
    void setupLostClaimMapsToStableErrorWithoutIssuingSession(RuntimeException failure) throws Exception {
        var request = requestWith(new SsoIntent(SsoIntent.Kind.SETUP, "/setup/admin", false, null, null, NOW));
        var session = (MockHttpSession) request.getSession(false);
        var response = new MockHttpServletResponse();
        when(ssoProperties.adminGroup()).thenReturn("picsou-admin");
        when(ssoAccountService.usernameFor(any())).thenReturn("alice");
        when(ssoAccountService.displayNameFor(any())).thenReturn("Alice");
        when(setupService.seedSsoAdmin(any(), eq("alice"), eq("Alice"))).thenThrow(failure);

        handler(false).onAuthenticationSuccess(request, response, aliceAuthentication());

        assertThat(response.getRedirectedUrl()).isEqualTo("/setup/admin?ssoError=SETUP_ALREADY_CLAIMED");
        assertThat(session.isInvalid()).isTrue();
        verifyNoInteractions(sessionIssuer, auditService);
    }

    static java.util.stream.Stream<RuntimeException> bootstrapFailures() {
        return java.util.stream.Stream.of(new IllegalStateException("claimed"),
            new org.springframework.dao.ConcurrencyFailureException("serialization failure"),
            new org.springframework.transaction.TransactionSystemException("commit failure"));
    }

    @Test
    void missingIntentExpiresAndInvalidatesSession() throws Exception {
        var request = requestWith(null);
        var session = (MockHttpSession) request.getSession(false);
        var response = new MockHttpServletResponse();
        handler(false).onAuthenticationSuccess(request, response, null);
        assertThat(response.getRedirectedUrl()).isEqualTo("/login?ssoError=SESSION_EXPIRED");
        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    void missingSessionIsNotCreated() throws Exception {
        var request = new MockHttpServletRequest();
        handler(false).onAuthenticationSuccess(request, new MockHttpServletResponse(), null);
        assertThat(request.getSession(false)).isNull();
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"authorization_request_not_found,SESSION_EXPIRED", "invalid_grant,IDP_ERROR"})
    void failuresUseStableCodesAndInvalidateHandshake(String error, String expected) throws Exception {
        var request = new MockHttpServletRequest();
        var session = new MockHttpSession();
        request.setSession(session);
        var response = new MockHttpServletResponse();
        new SsoAuthenticationFailureHandler().onAuthenticationFailure(request, response,
            new OAuth2AuthenticationException(new OAuth2Error(error, "untrusted\r\ndescription", null)));
        assertThat(response.getRedirectedUrl()).isEqualTo("/login?ssoError=" + expected);
        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    void resolvedLoginSyncsRoleThenIssuesSessionWithSsoClockAndRedirectsToSsoComplete() throws Exception {
        SsoIntent intent = loginIntent("/accounts", true);
        var request = requestWith(intent);
        var session = (MockHttpSession) request.getSession(false);
        var response = new MockHttpServletResponse();
        AppUser alice = user(1, "alice", UserRole.ADMIN, true);
        when(ssoAccountService.resolveLogin(any(), eq(request)))
            .thenReturn(new SsoAccountService.LoginOutcome.Resolved(alice, false));

        handler(false).onAuthenticationSuccess(request, response, aliceAuthentication());

        ArgumentCaptor<SsoIdentity> identity = ArgumentCaptor.forClass(SsoIdentity.class);
        verify(ssoAccountService).resolveLogin(identity.capture(), eq(request));
        assertThat(identity.getValue().subject()).isEqualTo("alice");
        assertThat(identity.getValue().groups()).contains(Set.of("picsou-admin"));
        InOrder order = inOrder(ssoRoleSyncService, sessionIssuer);
        order.verify(ssoRoleSyncService).sync(alice, Optional.of(Set.of("picsou-admin")), request);
        order.verify(sessionIssuer).issue(alice, true, false, NOW, request, response);
        assertThat(response.getRedirectedUrl()).isEqualTo("/login/sso-complete?redirect=%2Faccounts");
        assertThat(session.isInvalid()).isTrue();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @ParameterizedTest
    @CsvSource({"true", "false"})
    void rememberMeIsPassedThroughAndSeriesIsNeverTrustedFor2fa(boolean rememberMe) throws Exception {
        var request = requestWith(loginIntent("/accounts", rememberMe));
        var response = new MockHttpServletResponse();
        AppUser alice = user(1, "alice", UserRole.ADMIN, true);
        when(ssoAccountService.resolveLogin(any(), eq(request)))
            .thenReturn(new SsoAccountService.LoginOutcome.Resolved(alice, true));

        handler(false).onAuthenticationSuccess(request, response, aliceAuthentication());

        verify(sessionIssuer).issue(alice, rememberMe, false, NOW, request, response);
    }

    @Test
    void oauth2TargetRedirectsStraightToTheAuthorizationServer() throws Exception {
        SsoIntent intent = loginIntent("/oauth2/authorize?client_id=x", false);
        var request = requestWith(intent);
        var response = new MockHttpServletResponse();
        AppUser alice = user(1, "alice", UserRole.ADMIN, true);
        when(ssoAccountService.resolveLogin(any(), eq(request)))
            .thenReturn(new SsoAccountService.LoginOutcome.Resolved(alice, false));

        handler(false).onAuthenticationSuccess(request, response, aliceAuthentication());

        assertThat(response.getRedirectedUrl()).isEqualTo("/oauth2/authorize?client_id=x");
    }

    @Test
    void refusedRedirectsWithTheRefusalCodeAndIssuesNoSession() throws Exception {
        SsoIntent intent = loginIntent("/settings", false);
        var request = requestWith(intent);
        var session = (MockHttpSession) request.getSession(false);
        var response = new MockHttpServletResponse();
        when(ssoAccountService.resolveLogin(any(), eq(request)))
            .thenReturn(new SsoAccountService.LoginOutcome.Refused("NOT_LINKED"));

        handler(false).onAuthenticationSuccess(request, response, aliceAuthentication());

        assertThat(response.getRedirectedUrl()).isEqualTo(intent.errorRedirect("NOT_LINKED"));
        verify(ssoRoleSyncService, never()).sync(any(), any(), any());
        verifyNoInteractions(sessionIssuer);
        assertThat(session.isInvalid()).isTrue();
    }

    @ParameterizedTest
    @CsvSource({
        "MEMBER,false,ACCOUNT_INACTIVE",
        "ADMIN,false,ACCOUNT_INACTIVE",
        "MEMBER,true,ACCOUNT_INACTIVE",
        "ADMIN,true,ADMIN_RECOVERY_ACTIVE"})
    void inactiveAccountFollowsTheAuthControllerRecoveryRule(UserRole role, boolean recovery, String code)
            throws Exception {
        SsoIntent intent = loginIntent("/settings", false);
        var request = requestWith(intent);
        var response = new MockHttpServletResponse();
        when(ssoAccountService.resolveLogin(any(), eq(request)))
            .thenReturn(new SsoAccountService.LoginOutcome.Resolved(user(2, "bob", role, false), false));

        handler(recovery).onAuthenticationSuccess(request, response, aliceAuthentication());

        assertThat(response.getRedirectedUrl()).isEqualTo(intent.errorRedirect(code));
        verify(ssoRoleSyncService, never()).sync(any(), any(), any());
        verifyNoInteractions(sessionIssuer);
    }

    @Test
    void flowExceptionMapsToItsCodeAndStillDiscardsHandshake() throws Exception {
        SsoIntent intent = loginIntent("/accounts", false);
        var request = requestWith(intent);
        var session = (MockHttpSession) request.getSession(false);
        var response = new MockHttpServletResponse();
        when(ssoAccountService.resolveLogin(any(), eq(request)))
            .thenReturn(new SsoAccountService.LoginOutcome.Resolved(user(1, "alice", UserRole.ADMIN, true), false));
        when(ssoRoleSyncService.sync(any(), any(), any())).thenThrow(new SsoFlowException("REAUTH_REQUIRED"));

        handler(false).onAuthenticationSuccess(request, response, aliceAuthentication());

        assertThat(response.getRedirectedUrl()).isEqualTo(intent.errorRedirect("REAUTH_REQUIRED"));
        verifyNoInteractions(sessionIssuer);
        assertThat(session.isInvalid()).isTrue();
    }

    @ParameterizedTest
    @CsvSource({"0,true", "60,true", "61,false", "600,false", "-1,false"})
    void reauthRequiresFreshIdTokenAuthTime(long age, boolean accepted) throws Exception {
        var intent = new SsoIntent(SsoIntent.Kind.REAUTH, "/settings?resume=export", true, 1L, null, NOW);
        var request = requestWith(intent);
        var response = new MockHttpServletResponse();
        var alice = signedIn(request, 1L);
        when(ssoAccountService.isLinkedTo(any(), eq(1L))).thenReturn(true);
        if (accepted) when(jwtUtil.generateReauthProof(alice)).thenReturn("proof");

        handler(false).onAuthenticationSuccess(request, response, authenticatedAt(age));

        assertThat(response.getRedirectedUrl()).isEqualTo(accepted ? intent.redirect() : intent.errorRedirect("REAUTH_TOO_OLD"));
        if (accepted) {
            verify(ssoAccountService).touchLogin(any());
            verify(ssoRoleSyncService).sync(eq(alice), any(), eq(request));
            verify(cookieWriter).setReauthProof(response, "proof");
            verifyNoMoreInteractions(cookieWriter);
        } else {
            verifyNoInteractions(cookieWriter, ssoRoleSyncService);
            verify(ssoAccountService, never()).touchLogin(any());
        }
        verifyNoInteractions(sessionIssuer);
        assertThat(((MockHttpSession) request.getSession(false))).isNull();
    }

    @ParameterizedTest
    @CsvSource({"REAUTH,false", "LINK,false", "REAUTH,true", "LINK,true"})
    void callbackWithoutValidAccessTokenExpiresSession(SsoIntent.Kind kind, boolean invalidCookie) throws Exception {
        var intent = new SsoIntent(kind, "/settings", false, 1L, null, NOW);
        var request = requestWith(intent);
        if (invalidCookie) request.setCookies(new Cookie("access_token", "invalid"));
        var response = new MockHttpServletResponse();
        handler(false).onAuthenticationSuccess(request, response, aliceAuthentication());
        assertThat(response.getRedirectedUrl()).isEqualTo(intent.errorRedirect("SESSION_EXPIRED"));
        verifyNoInteractions(ssoAccountService, ssoRoleSyncService, cookieWriter, sessionIssuer);
    }

    @ParameterizedTest
    @EnumSource(value = SsoIntent.Kind.class, names = {"REAUTH", "LINK"})
    void callbackRefusesChangedLocalAccount(SsoIntent.Kind kind) throws Exception {
        var intent = new SsoIntent(kind, "/settings", false, 1L, null, NOW);
        var request = requestWith(intent);
        signedIn(request, 2L);
        var response = new MockHttpServletResponse();
        handler(false).onAuthenticationSuccess(request, response, aliceAuthentication());
        assertThat(response.getRedirectedUrl()).isEqualTo(intent.errorRedirect("REAUTH_WRONG_ACCOUNT"));
        verifyNoInteractions(ssoAccountService, reAuthService, cookieWriter, sessionIssuer);
    }

    @Test
    void reauthRefusesIdentityNotLinkedToSignedInUser() throws Exception {
        var intent = new SsoIntent(SsoIntent.Kind.REAUTH, "/settings", false, 1L, null, NOW);
        var request = requestWith(intent);
        signedIn(request, 1L);
        var response = new MockHttpServletResponse();
        handler(false).onAuthenticationSuccess(request, response, authenticatedAt(0));
        assertThat(response.getRedirectedUrl()).isEqualTo(intent.errorRedirect("REAUTH_WRONG_ACCOUNT"));
        verify(ssoAccountService, never()).touchLogin(any());
        verifyNoInteractions(ssoRoleSyncService, cookieWriter, sessionIssuer);
    }

    @ParameterizedTest
    @CsvSource({"/settings,/settings?sso=linked", "/settings?tab=security,/settings?tab=security&sso=linked"})
    void linkSyncsRoleAndAuditsWithoutIssuingSessionOrTouchingLogin(String redirect, String expected) throws Exception {
        var intent = new SsoIntent(SsoIntent.Kind.LINK, redirect, false, 1L, null, NOW);
        var request = requestWith(intent);
        var alice = signedIn(request, 1L);
        when(reAuthService.hasValidProof(alice, request)).thenReturn(true);
        when(ssoAccountService.link(eq(1L), any())).thenReturn(alice);
        var response = new MockHttpServletResponse();
        handler(false).onAuthenticationSuccess(request, response, aliceAuthentication());
        assertThat(response.getRedirectedUrl()).isEqualTo(expected);
        verify(ssoRoleSyncService).sync(eq(alice), any(), eq(request));
        verify(auditService).record(eq("sso.identity.linked"), eq("alice"), eq(request), any());
        verify(ssoAccountService, never()).touchLogin(any());
        verifyNoInteractions(cookieWriter, sessionIssuer);
    }

    @Test
    void linkConflictReturnsIdentityInUse() throws Exception {
        var intent = new SsoIntent(SsoIntent.Kind.LINK, "/settings", false, 1L, null, NOW);
        var request = requestWith(intent);
        var alice = signedIn(request, 1L);
        when(reAuthService.hasValidProof(alice, request)).thenReturn(true);
        when(ssoAccountService.link(eq(1L), any())).thenThrow(new SsoFlowException("IDENTITY_IN_USE"));
        var response = new MockHttpServletResponse();
        handler(false).onAuthenticationSuccess(request, response, aliceAuthentication());
        assertThat(response.getRedirectedUrl()).isEqualTo(intent.errorRedirect("IDENTITY_IN_USE"));
        verifyNoInteractions(ssoRoleSyncService, auditService, cookieWriter, sessionIssuer);
    }

    @Test
    void linkProofValidAtStartButExpiredAtCallbackNeverLinks() throws Exception {
        var request = new MockHttpServletRequest("POST", "/oauth2/sso/authorization/oidc");
        var alice = signedIn(request, 1L);
        when(reAuthService.hasValidProof(alice, request)).thenReturn(true, false);
        var repository = org.mockito.Mockito.mock(SsoClientRegistrationRepository.class);
        when(repository.isAvailable()).thenReturn(true);
        request.addParameter("intent", "link");
        var guard = new SsoStartGuardFilter(repository, org.mockito.Mockito.mock(com.picsou.service.SetupService.class),
            new HashMap<>(), Clock.fixed(NOW, ZoneOffset.UTC), authenticator, reAuthService,
            ssoProperties, org.mockito.Mockito.mock(com.picsou.repository.AppUserRepository.class));
        guard.doFilter(request, new MockHttpServletResponse(), new org.springframework.mock.web.MockFilterChain());
        var intent = (SsoIntent) request.getAttribute(SsoIntent.ATTRIBUTE);
        assertThat(intent).isNotNull();
        request.setAttribute(SsoAuthorizationRequestRepository.REMOVED_REQUEST_ATTRIBUTE,
            OAuth2AuthorizationRequest.authorizationCode().authorizationUri("https://idp.test/authorize")
                .clientId("picsou").attributes(a -> a.put(SsoIntent.ATTRIBUTE, intent)).build());
        var response = new MockHttpServletResponse();
        handler(false).onAuthenticationSuccess(request, response, aliceAuthentication());
        assertThat(response.getRedirectedUrl()).isEqualTo("/settings?ssoError=REAUTH_REQUIRED");
        verify(ssoAccountService, never()).link(any(), any());
        verifyNoInteractions(ssoRoleSyncService, auditService, cookieWriter, sessionIssuer);
    }

    private AppUser signedIn(MockHttpServletRequest request, long id) {
        var alice = user(id, "alice", UserRole.ADMIN, true);
        request.setCookies(new Cookie("access_token", "access"));
        when(authenticator.authenticate("access")).thenReturn(Optional.of(new UsernamePasswordAuthenticationToken(alice, null)));
        return alice;
    }

    private static Authentication authenticatedAt(long age) {
        var claims = new HashMap<String, Object>();
        claims.put("sub", "alice");
        claims.put("iss", "https://idp.test");
        claims.put("groups", List.of("picsou-admin"));
        if (age >= 0) claims.put("auth_time", NOW.minusSeconds(age));
        var token = new OidcIdToken("id-token", NOW.minusSeconds(900), NOW.plusSeconds(300), claims);
        return new UsernamePasswordAuthenticationToken(new DefaultOidcUser(List.of(), token, "sub"), null);
    }

    private static SsoIntent activateIntent(boolean rememberMe) {
        return new SsoIntent(SsoIntent.Kind.ACTIVATE, "/", rememberMe, null, "tok", Instant.EPOCH);
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void activateIssuesTheSessionAndClearsTheStalePersistentCookieUnlessRemembered(boolean rememberMe) throws Exception {
        var request = requestWith(activateIntent(rememberMe));
        var response = new MockHttpServletResponse();
        AppUser dora = user(9L, "dora", UserRole.MEMBER, true);
        when(ssoAccountService.activateWithIdentity(eq("tok"), any(SsoIdentity.class), eq(request))).thenReturn(dora);

        handler(false).onAuthenticationSuccess(request, response, aliceAuthentication());

        assertThat(response.getRedirectedUrl()).isEqualTo("/login/sso-complete?redirect=%2F");
        InOrder order = inOrder(ssoAccountService, ssoRoleSyncService, sessionIssuer);
        order.verify(ssoAccountService).activateWithIdentity(eq("tok"), any(SsoIdentity.class), eq(request));
        order.verify(ssoRoleSyncService).sync(eq(dora), any(), eq(request));
        order.verify(sessionIssuer).issue(dora, rememberMe, false, NOW, request, response);
        verify(cookieWriter, rememberMe ? never() : org.mockito.Mockito.times(1)).clearPersistent(response);
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"ACTIVATION_INVALID", "IDENTITY_IN_USE"})
    void activateRefusalRedirectsToTheActivationPageWithoutASession(String code) throws Exception {
        var request = requestWith(activateIntent(true));
        var session = (MockHttpSession) request.getSession(false);
        var response = new MockHttpServletResponse();
        when(ssoAccountService.activateWithIdentity(eq("tok"), any(SsoIdentity.class), eq(request)))
            .thenThrow(new SsoFlowException(code));

        handler(false).onAuthenticationSuccess(request, response, aliceAuthentication());

        assertThat(response.getRedirectedUrl()).isEqualTo("/activate/tok?ssoError=" + code);
        assertThat(session.isInvalid()).isTrue();
        verifyNoInteractions(sessionIssuer, ssoRoleSyncService);
    }
}
