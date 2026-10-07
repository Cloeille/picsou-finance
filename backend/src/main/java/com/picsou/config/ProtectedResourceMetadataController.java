package com.picsou.config;

import com.picsou.mcp.Scopes;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RFC 9728 OAuth 2.0 Protected Resource Metadata for the MCP resource ({@code /mcp}).
 *
 * <p>Unauthenticated by design: a remote-MCP client (claude.ai) fetches this document — pointed at
 * by the {@code WWW-Authenticate: Bearer resource_metadata="…"} header on an unauthenticated
 * {@code /mcp} request (see {@link McpAuthenticationEntryPoint}) — to discover the resource
 * identifier, the authorization server(s) that can mint a token for it, and the scopes it supports,
 * before starting the OAuth flow (discovery → DCR → authorize+consent → token).
 *
 * <p>Spring Security 6.4 / Authorization Server 1.4.5 have no built-in RFC 9728 support (only the
 * RFC 8414 authorization-server metadata at {@code /.well-known/oauth-authorization-server}, served
 * by the AS's own filter chain — see {@link AuthorizationServerConfig}), so this is a plain
 * controller on the API filter chain, same approach as {@link DynamicClientRegistrationController}.
 * Because it is a normal controller (not part of {@code OAuth2AuthorizationServerConfigurer}'s
 * endpoints matcher), the request is handled by {@link SecurityConfig}'s {@code @Order(2)} chain —
 * it must be {@code permitAll} there, which it is.
 */
@RestController
public class ProtectedResourceMetadataController {

    /** Also referenced by {@link SecurityConfig} to permit this path unauthenticated. */
    public static final String PATH = "/.well-known/oauth-protected-resource";

    @GetMapping(PATH)
    public Map<String, Object> metadata(HttpServletRequest request) {
        // Derived straight from the request (not a configured base-url property) so the document is
        // correct behind the reverse proxy: forward-headers-strategy: framework already rewrote
        // scheme/host/port from X-Forwarded-* before this controller runs.
        String base = ServletUriComponentsBuilder.fromContextPath(request).build().toUriString();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("resource", base + "/mcp");
        body.put("authorization_servers", List.of(base));
        body.put("scopes_supported", Scopes.ALL.stream().sorted().toList());
        body.put("bearer_methods_supported", List.of("header"));
        return body;
    }
}
