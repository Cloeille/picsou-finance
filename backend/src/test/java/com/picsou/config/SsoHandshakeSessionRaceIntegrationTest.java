package com.picsou.config;

import com.picsou.model.AppSetting;
import com.picsou.model.AppUser;
import com.picsou.model.FamilyMember;
import com.picsou.model.UserIdentity;
import com.picsou.model.UserRole;
import com.picsou.repository.AppSettingRepository;
import com.picsou.repository.AppUserRepository;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.UserIdentityRepository;
import jakarta.servlet.http.Cookie;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A signed-in browser keeps polling the API while its SSO handshake is in flight. Those requests
 * carry the handshake JSESSIONID plus the access cookie; they must leave the handshake session alone,
 * otherwise the callback arrives with a stale id and ends on SESSION_EXPIRED.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SsoHandshakeSessionRaceIntegrationTest {
    @Container @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    static final FakeOidcProvider IDP = provider();
    private static final int PROBES = 6;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("app.jwt.secret", () -> "test-jwt-secret-test-jwt-secret-0123456789");
        registry.add("app.crypto.encryption-key", () -> Base64.getEncoder().encodeToString(new byte[32]));
        registry.add("app.sso.issuer-uri", IDP::issuer);
        registry.add("app.sso.client-id", () -> "picsou-test");
        registry.add("app.sso.client-secret", () -> "test-secret");
    }

    @Autowired MockMvc mvc;
    @Autowired AppUserRepository users;
    @Autowired FamilyMemberRepository members;
    @Autowired UserIdentityRepository identities;
    @Autowired AppSettingRepository settings;
    @Autowired PasswordEncoder passwords;
    @Autowired JwtUtil jwt;

    @AfterAll static void stopProvider() { IDP.close(); }

    @Test
    void concurrentSignedInRequests_keepTheHandshakeSession_soTheCallbackStillFindsIt() throws Exception {
        AppUser alice = signedInUser();
        Cookie access = new Cookie("access_token", jwt.generateAccessToken(alice, Instant.now()));
        MvcResult started = mvc.perform(post("/oauth2/sso/authorization/oidc").cookie(access)
                .header("Sec-Fetch-Site", "same-origin").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("intent", "reauth"))
            .andExpect(status().isFound()).andReturn();
        MockHttpSession handshake = (MockHttpSession) started.getRequest().getSession(false);
        String handshakeId = handshake.getId();
        Map<String, String> parameters = FakeOidcProvider.params(
            URI.create(started.getResponse().getRedirectedUrl()).getRawQuery());
        String code = IDP.issueCode(parameters, "alice", Map.of("preferred_username", "alice"),
            Instant.now().minusSeconds(600));

        List<String> idsSeenByProbes = probeConcurrently(handshake, access);

        assertThat(handshake.getId()).isEqualTo(handshakeId);
        assertThat(idsSeenByProbes).containsOnly(handshakeId);
        MvcResult callback = mvc.perform(post("/oauth2/sso/code/oidc").session(handshake).cookie(access)
                .header("Sec-Fetch-Site", "same-origin").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("code", code).param("state", parameters.get("state")))
            .andExpect(status().isFound()).andReturn();
        assertThat(callback.getResponse().getRedirectedUrl()).isEqualTo("/settings?ssoError=REAUTH_TOO_OLD");
    }

    @Test
    void passwordLogin_neverBindsAuthenticationToAPreexistingSession() throws Exception {
        AppUser alice = signedInUser();
        MockHttpSession preexisting = new MockHttpSession();
        mvc.perform(post("/api/auth/login").session(preexisting).contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + alice.getUsername() + "\",\"password\":\"local-password\"}"))
            .andExpect(status().isOk());
        assertThat(preexisting.getAttribute("SPRING_SECURITY_CONTEXT")).isNull();
    }

    private List<String> probeConcurrently(MockHttpSession handshake, Cookie access) throws Exception {
        CyclicBarrier together = new CyclicBarrier(PROBES);
        ExecutorService pool = Executors.newFixedThreadPool(PROBES);
        try {
            List<Future<String>> results = new ArrayList<>();
            for (int i = 0; i < PROBES; i++) {
                String path = i % 2 == 0 ? "/actuator/health" : "/api/auth/sso";
                results.add(pool.submit(() -> {
                    together.await(10, TimeUnit.SECONDS);
                    mvc.perform(get(path).session(handshake).cookie(access)).andReturn();
                    return handshake.getId();
                }));
            }
            List<String> ids = new ArrayList<>();
            for (Future<String> result : results) ids.add(result.get(30, TimeUnit.SECONDS));
            return ids;
        } finally {
            pool.shutdownNow();
        }
    }

    private AppUser signedInUser() {
        settings.save(AppSetting.builder().key("setup.state").value("COMPLETE").build());
        return users.findByUsernameWithMember("alice").orElseGet(() -> {
            FamilyMember member = members.save(FamilyMember.builder().displayName("alice").build());
            AppUser alice = users.save(AppUser.builder().username("alice").passwordHash(passwords.encode("local-password"))
                .member(member).role(UserRole.MEMBER).activated(true).acknowledgedWarning(true).build());
            identities.save(UserIdentity.builder().user(alice).issuer(IDP.issuer()).subject("alice").build());
            return alice;
        });
    }

    private static FakeOidcProvider provider() {
        try { return new FakeOidcProvider(); }
        catch (java.io.IOException | com.nimbusds.jose.JOSEException e) { throw new ExceptionInInitializerError(e); }
    }
}
