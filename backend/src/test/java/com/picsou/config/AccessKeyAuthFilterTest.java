package com.picsou.config;

import com.picsou.mcp.AccessKeyService;
import com.picsou.mcp.AccessKeyService.ResolvedKey;
import com.picsou.model.AppUser;
import com.picsou.model.UserRole;
import com.picsou.repository.AppUserRepository;
import io.github.bucket4j.Bucket;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccessKeyAuthFilterTest {

    @Mock AccessKeyService accessKeyService;
    @Mock JwtTokenAuthenticator jwtTokenAuthenticator;
    @Mock AppUserRepository appUserRepository;
    @Mock FilterChain chain;

    AccessKeyAuthFilter filter;
    Map<Long, Bucket> keyBuckets;
    MockHttpServletRequest request;
    MockHttpServletResponse response;
    AppUser owner;

    static final Long KEY_ID = 42L;
    static final String VALID_KEY = "psk_abcd1234efgh5678ijkl9012mnop3456";

    @BeforeEach
    void setUp() {
        keyBuckets = new ConcurrentHashMap<>();
        filter = new AccessKeyAuthFilter(accessKeyService, keyBuckets, jwtTokenAuthenticator, appUserRepository);
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
        owner = AppUser.builder().id(7L).username("alice").role(UserRole.MEMBER).activated(true).build();
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ─── Property A: a key authenticates ONLY /mcp/**, never /api/** ──────

    @Test
    void shouldNotFilter_isTrue_forApiPaths() {
        request.setRequestURI("/api/accounts");
        assertThat(filter.shouldNotFilter(request)).isTrue();
    }

    @Test
    void shouldNotFilter_isTrue_forAdminPaths() {
        request.setRequestURI("/api/admin/users");
        assertThat(filter.shouldNotFilter(request)).isTrue();
    }

    @Test
    void shouldNotFilter_isFalse_forMcpRoot() {
        request.setRequestURI("/mcp");
        assertThat(filter.shouldNotFilter(request)).isFalse();
    }

    @Test
    void shouldNotFilter_isFalse_forMcpSubpath() {
        request.setRequestURI("/mcp/message");
        assertThat(filter.shouldNotFilter(request)).isFalse();
    }

    @Test
    void doesNotAuthenticate_onApiPath_evenWithValidBearerKey() throws Exception {
        // The strongest Property-A assertion: a real key presented to /api never
        // reaches validate(), because shouldNotFilter short-circuits doFilterInternal.
        request.setRequestURI("/api/accounts");
        request.addHeader("Authorization", "Bearer " + VALID_KEY);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verifyNoInteractions(accessKeyService);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    // ─── no usable key → pass through unauthenticated (→ 401 downstream) ──

    @Test
    void passesThrough_whenNoAuthorizationHeader() throws Exception {
        request.setRequestURI("/mcp");

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verifyNoInteractions(accessKeyService);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void passesThrough_whenAuthorizationIsNotBearerPsk() throws Exception {
        request.setRequestURI("/mcp");
        request.addHeader("Authorization", "Bearer someJwtToken");

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verifyNoInteractions(accessKeyService);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void passesThrough_whenKeyInvalid() throws Exception {
        request.setRequestURI("/mcp");
        request.addHeader("Authorization", "Bearer " + VALID_KEY);
        when(accessKeyService.validate(VALID_KEY)).thenReturn(Optional.empty());

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    // ─── happy path ──────────────────────────────────────────────────────

    @Test
    void setsAccessKeyAuthentication_onValidKey() throws Exception {
        request.setRequestURI("/mcp");
        request.addHeader("Authorization", "Bearer " + VALID_KEY);
        when(accessKeyService.validate(VALID_KEY))
            .thenReturn(Optional.of(new ResolvedKey(owner, Set.of("goals:read", "accounts:read"), KEY_ID)));

        filter.doFilter(request, response, chain);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isInstanceOf(AccessKeyAuthentication.class);
        assertThat(auth.getPrincipal()).isSameAs(owner);
        assertThat(auth.isAuthenticated()).isTrue();
        assertThat(auth.getAuthorities()).extracting(Object::toString)
            .containsExactlyInAnyOrder("goals:read", "accounts:read");
        verify(chain).doFilter(request, response);
    }

    // ─── Property C: scope authorities only, never a role ────────────────

    @Test
    void grantsScopeAuthoritiesOnly_neverRoleAdmin() throws Exception {
        request.setRequestURI("/mcp");
        request.addHeader("Authorization", "Bearer " + VALID_KEY);
        when(accessKeyService.validate(VALID_KEY))
            .thenReturn(Optional.of(new ResolvedKey(owner, Set.of("transactions:write"), KEY_ID)));

        filter.doFilter(request, response, chain);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth.getAuthorities()).extracting(Object::toString)
            .containsExactly("transactions:write")
            .doesNotContain("ROLE_ADMIN", "ROLE_MEMBER");
    }

    // ─── per-key rate limit ──────────────────────────────────────────────

    @Test
    void returns429_whenPerKeyRateLimitExceeded() throws Exception {
        request.setRequestURI("/mcp");
        request.addHeader("Authorization", "Bearer " + VALID_KEY);
        when(accessKeyService.validate(VALID_KEY))
            .thenReturn(Optional.of(new ResolvedKey(owner, Set.of("goals:read"), KEY_ID)));
        // Pre-seed an exhausted bucket for this key so the filter's tryConsume fails.
        Bucket drained = RateLimitConfig.createMcpKeyBucket();
        drained.tryConsumeAsMuchAsPossible();
        keyBuckets.put(KEY_ID, drained);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getContentType()).contains("application/problem+json");
        verify(chain, never()).doFilter(any(), any());
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    // ─── Task 5: MCP JWT (non-psk_ Bearer) on /mcp ─────────────────────────

    static final String MCP_JWT = "eyJhbGciOiJIUzI1NiJ9.mcp-token-body.signature";

    @Test
    void setsAccessKeyAuthentication_onValidMcpJwt() throws Exception {
        request.setRequestURI("/mcp");
        request.addHeader("Authorization", "Bearer " + MCP_JWT);
        when(jwtTokenAuthenticator.authenticateMcpToken(MCP_JWT))
            .thenReturn(Optional.of(new JwtTokenAuthenticator.McpPrincipal(7L, Set.of("goals:read", "accounts:read"))));
        when(appUserRepository.findByIdWithMember(7L)).thenReturn(Optional.of(owner));

        filter.doFilter(request, response, chain);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isInstanceOf(AccessKeyAuthentication.class);
        assertThat(auth.getPrincipal()).isSameAs(owner);
        assertThat(auth.isAuthenticated()).isTrue();
        assertThat(auth.getAuthorities()).extracting(Object::toString)
            .containsExactlyInAnyOrder("goals:read", "accounts:read");
        verify(chain).doFilter(request, response);
        verifyNoInteractions(accessKeyService);
    }

    @Test
    void mcpJwt_doesNotAuthenticate_onApiPath() throws Exception {
        // Property A: shouldNotFilter is true for /api/**, so the MCP-JWT path is never even reached.
        request.setRequestURI("/api/accounts");
        request.addHeader("Authorization", "Bearer " + MCP_JWT);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verifyNoInteractions(jwtTokenAuthenticator);
        verifyNoInteractions(accessKeyService);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void passesThrough_whenMcpJwtMalformedOrExpired() throws Exception {
        request.setRequestURI("/mcp");
        request.addHeader("Authorization", "Bearer " + MCP_JWT);
        when(jwtTokenAuthenticator.authenticateMcpToken(MCP_JWT)).thenReturn(Optional.empty());

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verifyNoInteractions(accessKeyService);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void mcpJwtPath_neverConsultsPerKeyThrottleBucket() throws Exception {
        // Short-lived, rotating tokens: no Bucket4j bucket is created for the MCP-JWT path.
        request.setRequestURI("/mcp");
        request.addHeader("Authorization", "Bearer " + MCP_JWT);
        when(jwtTokenAuthenticator.authenticateMcpToken(MCP_JWT))
            .thenReturn(Optional.of(new JwtTokenAuthenticator.McpPrincipal(7L, Set.of("goals:read"))));
        when(appUserRepository.findByIdWithMember(7L)).thenReturn(Optional.of(owner));

        filter.doFilter(request, response, chain);

        assertThat(keyBuckets).isEmpty();
        assertThat(response.getStatus()).isNotEqualTo(429);
    }

    @Test
    void pskPath_isUnchanged_whenAccessKeyServiceAndBucketsStillWired() throws Exception {
        // Regression guard for Task 5: the psk_ happy path (throttle bucket, ResolvedKey.keyId(),
        // AccessKeyAuthentication) is byte-for-byte the same as before the MCP-JWT branch existed.
        request.setRequestURI("/mcp");
        request.addHeader("Authorization", "Bearer " + VALID_KEY);
        when(accessKeyService.validate(VALID_KEY))
            .thenReturn(Optional.of(new ResolvedKey(owner, Set.of("goals:read", "accounts:read"), KEY_ID)));

        filter.doFilter(request, response, chain);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isInstanceOf(AccessKeyAuthentication.class);
        assertThat(((AccessKeyAuthentication) auth).getKeyId()).isEqualTo(KEY_ID);
        assertThat(keyBuckets).containsKey(KEY_ID);
        verifyNoInteractions(jwtTokenAuthenticator);
        verify(chain).doFilter(request, response);
    }
}
