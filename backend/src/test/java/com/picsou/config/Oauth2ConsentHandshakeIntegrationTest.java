package com.picsou.config;

import com.jayway.jsonpath.JsonPath;
import com.picsou.model.AppSetting;
import com.picsou.model.AppUser;
import com.picsou.model.FamilyMember;
import com.picsou.model.SetupState;
import com.picsou.model.UserRole;
import com.picsou.repository.AppSettingRepository;
import com.picsou.repository.AppUserRepository;
import com.picsou.repository.FamilyMemberRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.util.UriComponentsBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * C1 regression test: the interactive-consent handshake (DCR-registered clients, e.g. claude.ai)
 * must survive across the two separate {@code /oauth2/authorize} requests (the parking {@code GET}
 * and the consenting {@code POST}) that the SPA consent page drives with the SAME browser session.
 *
 * <p>Before the fix, {@link AppUser} implemented no principal interface, so
 * {@code AbstractAuthenticationToken.getName()} fell back to {@code Object#toString()} — an
 * identity-hash that differs on every freshly-loaded instance. {@link CookieBridgeAuthenticationFilter}
 * loads a fresh {@code AppUser} from the {@code access_token} cookie on every request and the
 * {@code SecurityContext} is never persisted between them (chain is {@code STATELESS}-equivalent:
 * {@code SessionCreationPolicy.IF_REQUIRED} with no session actually created), so the {@code
 * principal_name} Spring AS parks at the {@code GET} never equalled the one presented at the
 * {@code POST} — {@code OAuth2AuthorizationConsentAuthenticationProvider} rejects the mismatch with
 * {@code invalid_request}, and the {@code redirect_uri} for the consent-granting redirect is never
 * reached. This test walks the real handshake end-to-end (register → authorize → consent → token
 * exchange → list/revoke in Connected Apps) using one single access-token cookie throughout, exactly
 * as the SPA consent page does.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
@org.springframework.test.context.TestPropertySource(properties = {
    "logging.level.org.springframework.security=TRACE"
})
class Oauth2ConsentHandshakeIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void secrets(DynamicPropertyRegistry registry) {
        registry.add("app.jwt.secret", () -> "test-jwt-secret-test-jwt-secret-0123456789");
        registry.add("app.crypto.encryption-key", () -> Base64.getEncoder().encodeToString(new byte[32]));
    }

    @Autowired MockMvc mockMvc;
    @Autowired AppSettingRepository appSettingRepository;
    @Autowired AppUserRepository appUserRepository;
    @Autowired FamilyMemberRepository familyMemberRepository;
    @Autowired JwtUtil jwtUtil;

    private static final String REDIRECT_URI = "https://claude.ai/api/mcp/auth_callback";

    private Cookie authCookie;
    private String ownerUsername;

    @BeforeEach
    void completeSetupAndSeedOwner() {
        appSettingRepository.save(AppSetting.builder()
            .key("setup.state")
            .value(SetupState.COMPLETE.name())
            .build());

        FamilyMember member = familyMemberRepository.save(
            FamilyMember.builder().displayName("Alice").build());
        AppUser owner = appUserRepository.save(AppUser.builder()
            .username("alice-" + UUID.randomUUID())
            .passwordHash("$2a$12$abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQ")
            .role(UserRole.ADMIN)
            .activated(true)
            .tokenVersion(0L)
            .member(member)
            .build());
        ownerUsername = owner.getUsername();

        // The SAME access_token cookie is reused for every request below, exactly like the SPA
        // consent page does — CookieBridgeAuthenticationFilter re-derives (loads a fresh) AppUser
        // from it on each request, which is precisely the scenario C1 was breaking.
        authCookie = new Cookie("access_token", jwtUtil.generateAccessToken(owner));
    }

    @Test
    void twoRequestConsentHandshake_withTheSameCookie_issuesACode_notInvalidRequest() throws Exception {
        String clientId = registerDcrClient();

        String verifier = randomCodeVerifier();
        String challenge = s256Challenge(verifier);
        String clientState = "client-state-" + UUID.randomUUID();

        // 1) GET /oauth2/authorize — parks the authorization and redirects to the SPA consent page.
        // NOTE: Spring AS's OAuth2AuthorizationCodeRequestAuthenticationConverter reads a GET's
        // parameters from the raw query string (HttpServletRequest#getQueryString()), not just the
        // servlet parameter map — so the query string must be built directly into the request URI
        // (exactly like a real browser navigation), not attached via MockMvc's .param(...)/.queryParam(...)
        // builder methods.
        URI authorizeUri = UriComponentsBuilder.fromPath("/oauth2/authorize")
            .queryParam("response_type", "code")
            .queryParam("client_id", clientId)
            .queryParam("redirect_uri", REDIRECT_URI)
            .queryParam("scope", "accounts:read")
            .queryParam("state", clientState)
            .queryParam("code_challenge", challenge)
            .queryParam("code_challenge_method", "S256")
            .build().encode().toUri();

        MvcResult authorizeResult = mockMvc.perform(get(authorizeUri)
                .cookie(authCookie))
            .andDo(org.springframework.test.web.servlet.result.MockMvcResultHandlers.print())
            .andExpect(status().is3xxRedirection())
            .andReturn();

        String consentLocation = authorizeResult.getResponse().getHeader("Location");
        // Spring AS builds an ABSOLUTE consent redirect (e.g. http://localhost/consent?...), resolved
        // against the request/issuer — assert on the path, not a relative-prefix match.
        assertThat(URI.create(consentLocation).getPath())
            .as("must be parked at the SPA consent page, not rejected up front").isEqualTo("/consent");
        // getQueryParams() returns the still-percent-encoded value (state ends in "%3D" for base64
        // "=" padding); the real SPA reads it via URLSearchParams, which decodes automatically —
        // mirror that so the POST echoes the exact parked state, not "...%3D".
        String consentState = java.net.URLDecoder.decode(
            UriComponentsBuilder.fromUriString(consentLocation).build().getQueryParams().getFirst("state"),
            StandardCharsets.UTF_8);
        assertThat(consentState).isNotBlank();

        // 2) POST /oauth2/authorize — the SAME cookie, a fresh AppUser reload underneath, completing
        // consent for the authorization parked in step 1. This is the exact request C1 broke: before
        // the fix, the freshly-loaded principal's identity-hash name never equalled the principal
        // name parked at step 1, so Spring AS rejected this with "invalid_request".
        MvcResult consentResult = mockMvc.perform(post("/oauth2/authorize")
                .cookie(authCookie)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("client_id", clientId)
                .param("state", consentState)
                .param("scope", "accounts:read"))
            .andExpect(status().is3xxRedirection())
            .andReturn();

        String finalLocation = consentResult.getResponse().getHeader("Location");
        assertThat(finalLocation)
            .as("must redirect to the client's own redirect_uri carrying a code, not an error")
            .startsWith(REDIRECT_URI)
            .doesNotContain("error=invalid_request")
            .contains("code=");
        String returnedState = UriComponentsBuilder.fromUriString(finalLocation)
            .build().getQueryParams().getFirst("state");
        assertThat(returnedState).as("the client's own state must round-trip").isEqualTo(clientState);

        String code = java.net.URLDecoder.decode(
            UriComponentsBuilder.fromUriString(finalLocation).build().getQueryParams().getFirst("code"),
            StandardCharsets.UTF_8);
        assertThat(code).isNotBlank();

        // 3) POST /oauth2/token — exchange the code (PKCE) for an access token, exactly like the
        // real claude.ai client does after the redirect.
        MvcResult tokenResult = mockMvc.perform(post("/oauth2/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("grant_type", "authorization_code")
                .param("code", code)
                .param("redirect_uri", REDIRECT_URI)
                .param("client_id", clientId)
                .param("code_verifier", verifier))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.access_token").exists())
            .andReturn();
        String tokenResponse = tokenResult.getResponse().getContentAsString();
        assertThat((String) JsonPath.read(tokenResponse, "$.access_token")).isNotBlank();

        // 4) /api/connected-apps — the owner (same user, same cookie) now sees the granted app,
        // proving the persisted oauth2_authorization.principal_name matches AppUser.getUsername().
        String listResponse = mockMvc.perform(get("/api/connected-apps").cookie(authCookie))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(1)))
            .andExpect(jsonPath("$[0].clientName").value("claude.ai"))
            .andReturn().getResponse().getContentAsString();
        String authorizationId = JsonPath.read(listResponse, "$[0].id");

        // 5) DELETE revokes it — owner match works because principal_name == username on both sides.
        mockMvc.perform(delete("/api/connected-apps/{id}", authorizationId).cookie(authCookie))
            .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/connected-apps").cookie(authCookie))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(0)));
    }

    /**
     * Minimal safety-net assertions (per the fix's acceptance criteria), independent of the full
     * MockMvc round trip above: the principal name must be stable and value-based, not an
     * identity-hash that differs across freshly-loaded instances of "the same" user.
     */
    @Test
    void appUserPrincipalName_isStableAcrossFreshlyLoadedInstances() {
        AppUser loadedAtGet = appUserRepository.findByUsernameWithMember(ownerUsername).orElseThrow();
        AppUser loadedAtPost = appUserRepository.findByUsernameWithMember(ownerUsername).orElseThrow();

        // Two distinct JPA instances (as CookieBridgeAuthenticationFilter produces on each request)...
        assertThat(loadedAtGet).isNotSameAs(loadedAtPost);
        // ...but the same stable principal name, which is exactly what
        // OAuth2AuthorizationConsentAuthenticationProvider compares across the two /oauth2/authorize
        // requests.
        assertThat(loadedAtGet.getName()).isEqualTo(ownerUsername);
        assertThat(loadedAtGet.getName()).isEqualTo(loadedAtPost.getName());
    }

    private String registerDcrClient() throws Exception {
        String body = """
            {"client_name":"claude.ai","redirect_uris":["%s"],"scope":"accounts:read"}
            """.formatted(REDIRECT_URI);
        String response = mockMvc.perform(post("/oauth2/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.client_id");
    }

    private String randomCodeVerifier() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String s256Challenge(String verifier) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(verifier.getBytes(StandardCharsets.US_ASCII));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
    }
}
