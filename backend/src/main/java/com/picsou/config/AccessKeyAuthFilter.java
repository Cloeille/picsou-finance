package com.picsou.config;

import com.picsou.mcp.AccessKeyService;
import com.picsou.mcp.AccessKeyService.ResolvedKey;
import com.picsou.model.AppUser;
import com.picsou.repository.AppUserRepository;
import io.github.bucket4j.Bucket;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;

/**
 * Authenticates an MCP request that carries an {@code Authorization: Bearer} credential — either
 * a long-lived {@code psk_…} access-key, or (since the remote-MCP OAuth flow) a short-lived MCP
 * JWT minted by the embedded authorization server for a consenting third-party client (e.g.
 * claude.ai). The Bearer is routed by prefix: {@code psk_} → {@link AccessKeyService}; anything
 * else → {@link JwtTokenAuthenticator#authenticateMcpToken}. Both paths converge on the same
 * {@link AccessKeyAuthentication} principal, so the tool layer, {@code ScopeEnforcementAspect} and
 * {@code UserContext} do not need to know which credential kind authenticated the request.
 *
 * <p>This is the second authentication principal in the app, alongside the JWT cookie. Three
 * structural guarantees keep it confined to the curated MCP surface:
 * <ul>
 *   <li><b>Property A</b> — {@link #shouldNotFilter} returns {@code true} for any non-{@code /mcp}
 *       path, so neither a {@code psk_} key nor an MCP JWT presented to {@code /api/**} is ever even
 *       validated; it cannot set a {@link SecurityContextHolder}, so those endpoints answer 401.</li>
 *   <li><b>Property B</b> — the {@link AccessKeyAuthentication} type marks the request as key-driven,
 *       letting {@code UserContext} refuse the admin {@code ?memberId=} override.</li>
 *   <li><b>Property C</b> — authorities are scope strings only, never {@code ROLE_*}.</li>
 * </ul>
 *
 * <p>Runs last among the {@code UsernamePasswordAuthenticationFilter}-anchored filters. A per-key
 * Bucket4j throttle returns 429 {@code problem+json} on overflow for the {@code psk_} path only —
 * MCP JWTs are short-lived and rotate on their own, so no bucket is created for them.
 */
public class AccessKeyAuthFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String KEY_PREFIX = "psk_";

    private final AccessKeyService accessKeyService;
    private final Map<Long, Bucket> keyBuckets;
    private final JwtTokenAuthenticator jwtTokenAuthenticator;
    private final AppUserRepository appUserRepository;

    public AccessKeyAuthFilter(
        AccessKeyService accessKeyService,
        Map<Long, Bucket> keyBuckets,
        JwtTokenAuthenticator jwtTokenAuthenticator,
        AppUserRepository appUserRepository
    ) {
        this.accessKeyService = accessKeyService;
        this.keyBuckets = keyBuckets;
        this.jwtTokenAuthenticator = jwtTokenAuthenticator;
        this.appUserRepository = appUserRepository;
    }

    /** Property A: an access-key/MCP-JWT authenticates ONLY the MCP surface, never {@code /api/**}. */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/mcp");
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain chain
    ) throws ServletException, IOException {

        String bearer = extractBearer(request);
        if (bearer == null) {
            // No Authorization: Bearer header — leave unauthenticated; /mcp then answers 401.
            chain.doFilter(request, response);
            return;
        }

        if (bearer.startsWith(KEY_PREFIX)) {
            authenticateAccessKey(bearer, response, chain, request);
            return;
        }

        // Not a psk_ key: try it as an MCP JWT. No throttle bucket on this path (short-lived,
        // rotating tokens); an invalid/expired/forged token just leaves the request unauthenticated.
        authenticateMcpJwt(bearer);
        chain.doFilter(request, response);
    }

    private void authenticateAccessKey(
        String raw,
        HttpServletResponse response,
        FilterChain chain,
        HttpServletRequest request
    ) throws ServletException, IOException {
        Optional<ResolvedKey> resolved = accessKeyService.validate(raw);
        if (resolved.isEmpty()) {
            chain.doFilter(request, response);
            return;
        }
        ResolvedKey key = resolved.get();

        // Per-key throttle: created lazily on first use, shared across this key's requests.
        Bucket bucket = keyBuckets.computeIfAbsent(key.keyId(), id -> RateLimitConfig.createMcpKeyBucket());
        if (!bucket.tryConsume(1)) {
            writeTooManyRequests(response);
            return;
        }

        var authorities = key.scopes().stream()
            .map(SimpleGrantedAuthority::new)
            .toList();
        var authentication = new AccessKeyAuthentication(key.owner(), authorities, key.keyId());
        SecurityContextHolder.getContext().setAuthentication(authentication);

        chain.doFilter(request, response);
    }

    private void authenticateMcpJwt(String token) {
        jwtTokenAuthenticator.authenticateMcpToken(token).ifPresent(principal -> {
            Optional<AppUser> owner = appUserRepository.findByIdWithMember(principal.uid());
            if (owner.isEmpty()) {
                return;
            }
            var authorities = principal.scopes().stream()
                .map(SimpleGrantedAuthority::new)
                .toList();
            // No key id for an OAuth-issued token — this authentication was never a psk_ AccessKey row.
            var authentication = new AccessKeyAuthentication(owner.get(), authorities, null);
            SecurityContextHolder.getContext().setAuthentication(authentication);
        });
    }

    /** Pull the raw Bearer credential (key or JWT) out of the header, or {@code null} if absent. */
    private String extractBearer(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return null;
        }
        return header.substring(BEARER_PREFIX.length()).trim();
    }

    private void writeTooManyRequests(HttpServletResponse response) throws IOException {
        response.setStatus(429);
        response.setContentType("application/problem+json");
        response.getWriter().write("""
            {"status":429,"title":"Too Many Requests","detail":"Rate limit exceeded for this access key"}
            """);
    }
}
