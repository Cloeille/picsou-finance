package com.picsou.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.io.IOException;

/**
 * RFC 9728-flavoured 401 challenge for the MCP resource: an unauthenticated {@code /mcp/**}
 * request gets a {@code WWW-Authenticate} header pointing at the protected-resource metadata
 * document, so a standards-compliant remote-MCP client (claude.ai) can discover the authorization
 * server and start the OAuth flow — instead of the generic {@code application/problem+json} body
 * every other unauthenticated {@code /api/**} request gets (see {@link SecurityConfig}).
 *
 * <p>Wired via {@code exceptionHandling().defaultAuthenticationEntryPointFor(new
 * McpAuthenticationEntryPoint(), new AntPathRequestMatcher("/mcp/**"))} so only the MCP surface is
 * affected; every other path keeps the existing entry point untouched.
 *
 * <p>The base URL is derived from the {@link HttpServletRequest} itself via {@link
 * ServletUriComponentsBuilder#fromContextPath}, <em>not</em> {@code fromCurrentContextPath()} —
 * this entry point runs inside Spring Security's filter chain (from {@code
 * ExceptionTranslationFilter}), which short-circuits the response before the request ever reaches
 * {@code DispatcherServlet}, so {@code RequestContextHolder} has no thread-bound attributes yet at
 * this point. Reading straight off the request works regardless, and — because {@code
 * server.forward-headers-strategy: framework} wraps the request with {@code
 * ForwardedHeaderFilter} ahead of the security chain — still honours {@code X-Forwarded-*} behind
 * the reverse proxy.
 */
public class McpAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private static final String METADATA_PATH = "/.well-known/oauth-protected-resource";

    @Override
    public void commence(
        HttpServletRequest request,
        HttpServletResponse response,
        AuthenticationException authException
    ) throws IOException {
        String base = ServletUriComponentsBuilder.fromContextPath(request).build().toUriString();
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setHeader("WWW-Authenticate", "Bearer resource_metadata=\"" + base + METADATA_PATH + "\"");
    }
}
