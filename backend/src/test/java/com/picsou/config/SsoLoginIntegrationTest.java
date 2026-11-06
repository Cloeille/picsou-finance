package com.picsou.config;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.jayway.jsonpath.JsonPath;
import com.picsou.model.AppUser;
import com.picsou.model.FamilyMember;
import com.picsou.model.PersistentSession;
import com.picsou.model.UserIdentity;
import com.picsou.model.UserMfa;
import com.picsou.model.UserRole;
import com.picsou.repository.AppUserRepository;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.PersistentSessionRepository;
import com.picsou.repository.UserIdentityRepository;
import com.picsou.repository.UserMfaRepository;
import com.picsou.service.FamilyService;
import jakarta.servlet.http.Cookie;
import java.net.URI;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.util.UriComponentsBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// allow: SIZE_OK - the required 20 ordered scenarios share one bootstrap lifecycle and one real Spring context.
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
@TestMethodOrder(OrderAnnotation.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SsoLoginIntegrationTest {
    @Container @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    static final FakeOidcProvider IDP = provider();
    private static final String START = "/oauth2/sso/authorization/oidc";
    private static final String CALLBACK = "/oauth2/sso/code/oidc";
    private static final String DCR_REDIRECT = "https://claude.ai/api/mcp/auth_callback";
    private static final String VERIFIER = "a-deterministic-pkce-verifier-with-at-least-43-characters";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("app.jwt.secret", () -> "test-jwt-secret-test-jwt-secret-0123456789");
        registry.add("app.crypto.encryption-key", () -> Base64.getEncoder().encodeToString(new byte[32]));
        registry.add("app.sso.issuer-uri", IDP::issuer);
        registry.add("app.sso.client-id", () -> "picsou-test");
        registry.add("app.sso.client-secret", () -> "test-secret");
        registry.add("app.sso.admin-group", () -> "picsou-admin");
    }

    @Autowired MockMvc mvc;
    @Autowired AppUserRepository users;
    @Autowired FamilyMemberRepository members;
    @Autowired UserIdentityRepository identities;
    @Autowired PersistentSessionRepository sessions;
    @Autowired UserMfaRepository mfa;
    @Autowired PasswordEncoder passwords;
    @Autowired FamilyService family;
    @Autowired JwtUtil jwt;
    @Autowired JWKSource<SecurityContext> signingKeys;
    @Autowired ObjectProvider<OAuth2AuthorizedClientService> authorizedClients;

    @AfterAll static void stopProvider() { IDP.close(); }

    @Test @Order(1)
    void login_whilePendingAdmin_neverAuthorizes() throws Exception {
        int before = IDP.calls("/authorize");
        mvc.perform(start("login")).andExpect(redirectedUrl("/setup"));
        assertThat(IDP.calls("/authorize")).isEqualTo(before);
        assertThat(users.count()).isZero();
    }

    @Test @Order(2)
    void setup_claimsFirstAdmin_once_thenCompletes() throws Exception {
        MvcResult result = login("setup", "alice", true);
        assertThat(result.getResponse().getRedirectedUrl()).isEqualTo("/setup/security");
        assertThat(cookie(result, "refresh_token").getValue()).isNotBlank();
        AppUser alice = user("alice");
        assertThat(alice.getRole()).isEqualTo(UserRole.ADMIN);
        assertThat(alice.getPasswordHash()).isEmpty();
        assertThat(alice.isActivated()).isTrue();
        assertThat(identities.countByUserId(alice.getId())).isEqualTo(1);
        mvc.perform(start("setup")).andExpect(redirectedUrl("/setup/admin?ssoError=SETUP_ALREADY_CLAIMED"));
        mvc.perform(post("/api/setup/complete").header("Sec-Fetch-Site", "same-origin"))
            .andExpect(status().isNoContent());
    }

    @Test @Order(3)
    void unlinkedNonMember_isRefused_withoutProvisioning() throws Exception {
        long count = users.count();
        assertThat(login("login", "bob", false).getResponse().getRedirectedUrl()).isEqualTo("/login?ssoError=NOT_LINKED");
        assertThat(users.count()).isEqualTo(count);
    }

    @Test @Order(4)
    void matchingUsernameAndEmail_neverAutoLinksLocalAccount() throws Exception {
        AppUser carol = localUser("carol");
        long count = users.count();
        assertThat(login("login", "carol", false).getResponse().getRedirectedUrl()).isEqualTo("/login?ssoError=NOT_LINKED");
        assertThat(identities.countByUserId(carol.getId())).isZero();
        assertThat(users.count()).isEqualTo(count);
    }

    @Test @Order(5)
    void adminGroup_provisionsPasswordlessAdmin_passwordLoginFails() throws Exception {
        assertThat(login("login", "dave", true).getResponse().getRedirectedUrl()).startsWith("/login/sso-complete?");
        AppUser dave = user("dave");
        assertThat(dave.getRole()).isEqualTo(UserRole.ADMIN);
        assertThat(dave.getPasswordHash()).isEmpty();
        assertThat(identities.countByUserId(dave.getId())).isEqualTo(1);
        mvc.perform(passwordLogin("dave")).andExpect(status().isUnauthorized());
    }

    @Test @Order(6)
    void removedGroup_demotesMember_butPreservesLastAdmin() throws Exception {
        assertThat(login("login", "dave", false).getResponse().getRedirectedUrl()).startsWith("/login/sso-complete?");
        assertThat(user("dave").getRole()).isEqualTo(UserRole.MEMBER);
        assertThat(login("login", "alice", false).getResponse().getRedirectedUrl()).startsWith("/login/sso-complete?");
        assertThat(user("alice").getRole()).isEqualTo(UserRole.ADMIN);
    }

    @Test @Order(7)
    void pkceAndNonce_roundTrip_andTamperedVerifierOrNonceFails() throws Exception {
        Flow flow = begin(start("login"), "dave", false);
        assertThat(finish(flow).getResponse().getRedirectedUrl()).startsWith("/login/sso-complete?");
        Map<String, String> authorization = IDP.authorization(flow.code());
        assertThat(authorization.get("code_challenge_method")).isEqualTo("S256");
        assertThat(authorization.get("nonce")).isNotBlank();
        assertThat(authorization.get("code_challenge")).isEqualTo(FakeOidcProvider.s256(IDP.exchange(flow.code()).get("code_verifier")));
        IDP.tamperNextVerifier.set(true);
        assertThat(login("login", "dave", false).getResponse().getRedirectedUrl()).isEqualTo("/login?ssoError=IDP_ERROR");
        IDP.tamperNextNonce.set(true);
        assertThat(login("login", "dave", false).getResponse().getRedirectedUrl()).isEqualTo("/login?ssoError=IDP_ERROR");
    }

    @Test @Order(8)
    void rememberMe_restoresSsoWithLocalMfa_butNeverTrustsPasswordLogin() throws Exception {
        AppUser carol = user("carol");
        identities.save(UserIdentity.builder().user(carol).issuer(IDP.issuer()).subject("carol").email("carol@example.test").build());
        mfa.save(UserMfa.builder().user(carol).enabled(true).totpSecretEnc("unused-for-password-challenge").build());
        Flow flow = begin(start("login").param("rememberMe", "1"), "carol", false);
        MvcResult signedIn = finish(flow);
        Cookie persistent = cookie(signedIn, "persistent_token");
        PersistentSession series = series(persistent);
        assertThat(series.getSsoAuthenticatedAt()).isNotNull();
        assertThat(series.isTrustedFor2fa()).isFalse();
        MvcResult restored = mvc.perform(post("/api/auth/refresh").cookie(persistent))
            .andExpect(status().isOk()).andExpect(jsonPath("$.username").value("carol")).andReturn();
        Cookie rotated = cookie(restored, "persistent_token");
        assertThat(rotated.getValue()).isNotBlank();
        assertThat(series(rotated).getRevokedAt()).isNull();
        mvc.perform(passwordLogin("carol").cookie(rotated, cookie(restored, "access_token"), cookie(restored, "refresh_token")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.mfaRequired").value(true));
    }

    @Test @Order(9)
    void ssoWindow_boundsRefreshAccessPersistentAndNewAppAuthorizations() throws Exception {
        AppUser alice = user("alice");
        Instant old = Instant.now().minus(25, ChronoUnit.HOURS);
        Cookie staleAccess = new Cookie("access_token", jwt.generateAccessToken(alice, old));
        mvc.perform(post("/api/auth/refresh").cookie(new Cookie("refresh_token", jwt.generateRefreshToken(alice, null, old))))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("SSO_REVALIDATE"));
        mvc.perform(post("/api/auth/refresh").cookie(staleAccess))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("SSO_REVALIDATE"));
        mvc.perform(post("/api/auth/refresh").header("Authorization", "Bearer " + staleAccess.getValue()))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("SSO_REVALIDATE"));
        Instant now = Instant.now();
        String appToken = new NimbusJwtEncoder(signingKeys).encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),
            JwtClaimsSet.builder().issuer("http://localhost").audience(List.of("picsou-ios")).subject("alice")
                .issuedAt(now).expiresAt(now.plusSeconds(300)).claim("uid", alice.getId()).claim("tv", alice.getTokenVersion())
                .claim("role", "ADMIN").claim("type", "access").build())).getTokenValue();
        MvcResult refused = mvc.perform(post("/api/auth/refresh").header("Authorization", "Bearer " + appToken))
            .andExpect(status().isUnauthorized()).andReturn();
        assertThat(refused.getResponse().getHeaders("Set-Cookie")).noneMatch(h -> h.matches("^(access_token|refresh_token)=[^;]+.*"));
        String expiredLocation = authorize("picsou-ios", staleAccess).getResponse().getRedirectedUrl();
        assertThat(expiredLocation).startsWith("/login?redirect=");
        assertThat(authorize("picsou-ios", access("alice")).getResponse().getRedirectedUrl()).startsWith("picsou://callback?code=");
        assertThat(authorize("picsou-ios", new Cookie("access_token", jwt.generateAccessToken(alice)))
            .getResponse().getRedirectedUrl()).startsWith("picsou://callback?code=");
        String client = registerClient();
        MvcResult parked = authorize(client, access("alice"));
        assertThat(URI.create(parked.getResponse().getRedirectedUrl()).getPath()).isEqualTo("/consent");
        assertThat(consent(client, parked, staleAccess).getResponse().getRedirectedUrl()).startsWith(DCR_REDIRECT + "?code=");
        MvcResult persistentLogin = finish(begin(start("login").param("rememberMe", "1"), "dave", false));
        Cookie persistent = cookie(persistentLogin, "persistent_token");
        PersistentSession series = series(persistent);
        series.setSsoAuthenticatedAt(old);
        sessions.save(series);
        mvc.perform(post("/api/auth/refresh").cookie(persistent)).andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("SSO_REVALIDATE"));
        assertThat(sessions.findBySeriesId(series.getSeriesId()).orElseThrow().getRevokedAt()).isNotNull();
    }

    @Test @Order(10)
    void reauth_requiresFreshAuthTime_andProofAuthorizesExport() throws Exception {
        Cookie access = access("alice");
        Flow old = begin(start("reauth").cookie(access), "alice", true);
        String oldCode = IDP.issueCode(old.parameters(), "alice", claims("alice", true), Instant.now().minusSeconds(300));
        old = new Flow(old.session(), old.parameters(), oldCode);
        assertThat(finish(old, access).getResponse().getRedirectedUrl()).isEqualTo("/settings?ssoError=REAUTH_TOO_OLD");
        MvcResult fresh = finish(begin(start("reauth").cookie(access), "alice", true), access);
        Cookie proof = cookie(fresh, "reauth_proof");
        assertThat(proof.getValue()).isNotBlank();
        mvc.perform(post("/api/me/export").cookie(access, proof).contentType(MediaType.APPLICATION_JSON)
            .content("{\"includeBalanceSnapshots\":false}")).andExpect(status().isOk());
    }

    @Test @Order(11)
    void activation_bindsInvitation_andLeavesBlankPassword() throws Exception {
        AppUser invited = localUser("invited");
        invited.setActivated(false);
        invited.setActivationToken("invitation-token");
        invited.setActivationTokenExpires(Instant.now().plusSeconds(300));
        users.save(invited);
        MvcResult result = finish(begin(start("activate").param("token", "invitation-token").param("ack", "1"), "invited-sub", false));
        assertThat(result.getResponse().getRedirectedUrl()).startsWith("/login/sso-complete?");
        assertThat(cookie(result, "refresh_token").getValue()).isNotBlank();
        AppUser activated = user("invited");
        assertThat(activated.isActivated()).isTrue();
        assertThat(activated.getPasswordHash()).isEmpty();
        assertThat(activated.getActivationToken()).isNull();
        assertThat(identities.findByIssuerAndSubject(IDP.issuer(), "invited-sub").orElseThrow().getUser().getId())
            .isEqualTo(invited.getId());
    }

    @Test @Order(12)
    void postOnlyHandshake_preservesRedirectUri_andRejectsReplayAndCrossSite() throws Exception {
        Flow flow = begin(start("login"), "dave", false);
        assertThat(finish(flow).getResponse().getRedirectedUrl()).startsWith("/login/sso-complete?");
        assertThat(IDP.authorization(flow.code()).get("redirect_uri")).isEqualTo("http://localhost/login/sso/callback");
        assertThat(IDP.exchange(flow.code()).get("redirect_uri")).isEqualTo("http://localhost/login/sso/callback");
        Map<String, Integer> before = IDP.calls();
        mvc.perform(get(START)).andExpect(redirectedUrl("/login"));
        mvc.perform(get(CALLBACK)).andExpect(redirectedUrl("/login"));
        mvc.perform(post(START).header("Sec-Fetch-Site", "cross-site")).andExpect(status().isForbidden());
        mvc.perform(post(CALLBACK).header("Sec-Fetch-Site", "cross-site")).andExpect(status().isForbidden());
        mvc.perform(post(CALLBACK).header("Sec-Fetch-Site", "same-origin").param("code", flow.code())
            .param("state", flow.parameters().get("state"))).andExpect(redirectedUrl("/login?ssoError=SESSION_EXPIRED"));
        assertThat(IDP.calls()).isEqualTo(before);
        Flow first = begin(start("login"), "dave", false);
        Flow second = begin(start("login").session(first.session()), "dave", false);
        assertThat(finish(first).getResponse().getRedirectedUrl()).isEqualTo("/login?ssoError=SESSION_EXPIRED");
        assertThat(IDP.exchange(second.code())).isNull();
    }

    @Test @Order(13)
    void idpTokens_areNeverRetainedAsAuthorizedClients() throws Exception {
        assertThat(login("login", "dave", false).getResponse().getRedirectedUrl()).startsWith("/login/sso-complete?");
        assertThat(authorizedClients.orderedStream()).as("an OAuth2AuthorizedClientService bean must be registered").isNotEmpty();
        authorizedClients.orderedStream().forEach(service -> {
            OAuth2AuthorizedClient stored = service.loadAuthorizedClient("oidc", "dave");
            assertThat(stored).as("IdP tokens must be discarded, not saved for the OIDC principal").isNull();
        });
    }

    @Test @Order(14)
    void crlfSubject_isRefused_withoutRawControlCharactersInSsoLogs() throws Exception {
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
        List<ILoggingEvent> events;
        long count = users.count();
        try {
            assertThat(login("login", "attacker\r\nforged", false).getResponse().getRedirectedUrl())
                .isEqualTo("/login?ssoError=NOT_LINKED");
        } finally {
            root.detachAppender(appender);
            synchronized (appender) { events = List.copyOf(appender.list); }
            appender.stop();
        }
        assertThat(users.count()).isEqualTo(count);
        assertThat(events.stream().filter(e -> e.getLoggerName().startsWith("com.picsou.")
            && (e.getLoggerName().contains("Sso") || e.getLoggerName().contains(".sso."))))
            .allSatisfy(e -> assertThat(e.getFormattedMessage()).doesNotContain("\r", "\n"));
    }

    @Test @Order(15)
    void publicAppClients_receiveAccessTokens_withoutRefreshTokens() throws Exception {
        for (String client : List.of("picsou-ios", registerClient())) {
            Cookie access = access("alice");
            MvcResult authorization = authorize(client, access);
            if (!client.equals("picsou-ios")) authorization = consent(client, authorization, access);
            String location = authorization.getResponse().getRedirectedUrl();
            assertThat(location).startsWith(redirect(client) + "?code=");
            String code = FakeOidcProvider.params(URI.create(location).getRawQuery()).get("code");
            mvc.perform(post("/oauth2/token").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .param("grant_type", "authorization_code").param("code", code).param("client_id", client)
                    .param("redirect_uri", redirect(client)).param("code_verifier", VERIFIER))
                .andExpect(status().isOk()).andExpect(jsonPath("$.access_token").isNotEmpty())
                .andExpect(jsonPath("$.refresh_token").doesNotExist());
        }
    }

    @Test @Order(16)
    void passwordResetLink_neverLetsAnIdpIdentityTakeOverAnActivatedAccount() throws Exception {
        AppUser victim = localUser("resetvictim");
        mfa.save(UserMfa.builder().user(victim).enabled(true).totpSecretEnc("unused-for-this-scenario").build());
        String passwordHash = victim.getPasswordHash();
        String resetToken = family.resetPasswordToken(victim.getMember().getId());
        int authorizations = IDP.calls("/authorize");

        mvc.perform(start("activate").param("token", resetToken).param("ack", "1"))
            .andExpect(redirectedUrl("/activate/" + resetToken + "?ssoError=ACTIVATION_INVALID"));

        assertThat(IDP.calls("/authorize")).isEqualTo(authorizations);
        assertThat(identities.countByUserId(victim.getId())).isZero();
        assertThat(mfa.existsByUserIdAndEnabledTrue(victim.getId())).isTrue();
        assertThat(user("resetvictim").getPasswordHash()).isEqualTo(passwordHash);
        mvc.perform(post("/api/auth/activate/" + resetToken).header("Sec-Fetch-Site", "same-origin")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"a-brand-new-password\",\"acknowledgedWarning\":true}"))
            .andExpect(status().isOk());
        assertThat(passwords.matches("a-brand-new-password", user("resetvictim").getPasswordHash())).isTrue();
    }

    @Test @Order(17)
    void idToken_withBadSignature_isRejected() throws Exception {
        assertIdTokenRejected(FakeOidcProvider.IdTokenFault.BAD_SIGNATURE);
    }

    @Test @Order(18)
    void idToken_forAnotherAudience_isRejected() throws Exception {
        assertIdTokenRejected(FakeOidcProvider.IdTokenFault.WRONG_AUDIENCE);
    }

    @Test @Order(19)
    void idToken_fromAnotherIssuer_isRejected() throws Exception {
        assertIdTokenRejected(FakeOidcProvider.IdTokenFault.WRONG_ISSUER);
    }

    @Test @Order(20)
    void expiredIdToken_isRejected() throws Exception {
        assertIdTokenRejected(FakeOidcProvider.IdTokenFault.EXPIRED);
    }

    /** "dave" is linked and would sign in with a valid token, so the refusal can only come from the token. */
    private void assertIdTokenRejected(FakeOidcProvider.IdTokenFault fault) throws Exception {
        Flow flow = begin(start("login"), "dave", false);
        int userinfoCalls = IDP.calls("/userinfo");
        IDP.faultNextIdToken.set(fault);
        MvcResult result = finish(flow);
        assertThat(result.getResponse().getRedirectedUrl()).isEqualTo("/login?ssoError=IDP_ERROR");
        assertThat(result.getResponse().getHeaders("Set-Cookie")).noneMatch(h -> h.startsWith("access_token=")
            || h.startsWith("refresh_token=") || h.startsWith("persistent_token="));
        assertThat(IDP.calls("/userinfo")).as("the ID token is validated before userinfo is called").isEqualTo(userinfoCalls);
    }

    private record Flow(MockHttpSession session, Map<String, String> parameters, String code) {}
    private Flow begin(MockHttpServletRequestBuilder request, String subject, boolean admin) throws Exception {
        MvcResult result = mvc.perform(request).andExpect(status().isFound()).andReturn();
        String location = result.getResponse().getRedirectedUrl();
        assertThat(location).startsWith(IDP.issuer() + "/authorize?");
        Map<String, String> parameters = FakeOidcProvider.params(URI.create(location).getRawQuery());
        String code = IDP.issueCode(parameters, subject, claims(subject, admin), Instant.now());
        return new Flow((MockHttpSession) result.getRequest().getSession(false), parameters, code);
    }
    private MvcResult finish(Flow flow, Cookie... cookies) throws Exception {
        MockHttpServletRequestBuilder request = post(CALLBACK).session(flow.session())
            .header("Sec-Fetch-Site", "same-origin").contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .param("code", flow.code()).param("state", flow.parameters().get("state"));
        if (cookies.length > 0) request.cookie(cookies);
        return mvc.perform(request)
            .andExpect(status().isFound()).andReturn();
    }
    private MvcResult login(String intent, String subject, boolean admin) throws Exception {
        return finish(begin(start(intent), subject, admin));
    }
    private static MockHttpServletRequestBuilder start(String intent) {
        return post(START).header("Sec-Fetch-Site", "same-origin").contentType(MediaType.APPLICATION_FORM_URLENCODED).param("intent", intent);
    }
    private static Map<String, Object> claims(String name, boolean admin) {
        return Map.of("preferred_username", name, "email", name + "@example.test", "name", name,
            "groups", admin ? List.of("picsou-admin") : List.of());
    }
    private AppUser localUser(String name) {
        FamilyMember member = members.save(FamilyMember.builder().displayName(name).build());
        return users.save(AppUser.builder().username(name).passwordHash(passwords.encode("local-password"))
            .member(member).role(UserRole.MEMBER).activated(true).acknowledgedWarning(true).build());
    }
    private AppUser user(String name) { return users.findByUsernameWithMember(name).orElseThrow(); }
    private Cookie access(String name) { return new Cookie("access_token", jwt.generateAccessToken(user(name), Instant.now())); }
    private static Cookie cookie(MvcResult result, String name) {
        String header = result.getResponse().getHeaders("Set-Cookie").stream().filter(h -> h.startsWith(name + "="))
            .filter(h -> !h.startsWith(name + "=;")).findFirst().orElseThrow();
        return new Cookie(name, header.substring(name.length() + 1).split(";", 2)[0]);
    }
    private PersistentSession series(Cookie cookie) {
        return sessions.findBySeriesId(UUID.fromString(cookie.getValue().split(":", 2)[0])).orElseThrow();
    }
    private static MockHttpServletRequestBuilder passwordLogin(String name) {
        return post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"" + name + "\",\"password\":\"local-password\"}");
    }
    private String registerClient() throws Exception {
        String body = mvc.perform(post("/oauth2/register").contentType(MediaType.APPLICATION_JSON)
            .content("{\"client_name\":\"SSO integration\",\"redirect_uris\":[\"" + DCR_REDIRECT + "\"],\"scope\":\"accounts:read\"}"))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.client_id");
    }
    private MvcResult authorize(String client, Cookie access) throws Exception {
        URI uri = UriComponentsBuilder.fromPath("/oauth2/authorize").queryParam("response_type", "code")
            .queryParam("client_id", client).queryParam("redirect_uri", redirect(client))
            .queryParam("scope", client.equals("picsou-ios") ? "read" : "accounts:read")
            .queryParam("state", UUID.randomUUID()).queryParam("code_challenge", FakeOidcProvider.s256(VERIFIER))
            .queryParam("code_challenge_method", "S256").build().encode().toUri();
        return mvc.perform(get(uri).cookie(access)).andExpect(status().isFound()).andReturn();
    }
    private MvcResult consent(String client, MvcResult parked, Cookie access) throws Exception {
        String state = FakeOidcProvider.params(URI.create(parked.getResponse().getRedirectedUrl()).getRawQuery()).get("state");
        return mvc.perform(post("/oauth2/authorize").cookie(access).contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .param("client_id", client).param("state", state).param("scope", "accounts:read"))
            .andExpect(status().isFound()).andReturn();
    }
    private static String redirect(String client) { return client.equals("picsou-ios") ? "picsou://callback" : DCR_REDIRECT; }
    private static FakeOidcProvider provider() {
        try { return new FakeOidcProvider(); }
        catch (java.io.IOException | com.nimbusds.jose.JOSEException e) { throw new ExceptionInInitializerError(e); }
    }
}
