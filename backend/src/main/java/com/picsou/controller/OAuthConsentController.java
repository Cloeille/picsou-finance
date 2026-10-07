package com.picsou.controller;

import com.picsou.dto.ConsentInfoResponse;
import com.picsou.exception.ResourceNotFoundException;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Backs the SPA consent screen (Task 11): Spring Authorization Server redirects the browser to
 * {@code /consent?scope=&client_id=&state=} (see {@code AuthorizationServerConfig}'s
 * {@code consentPage("/consent")} wiring) when an interactive-consent client
 * (every DCR-registered remote-MCP client — {@link com.picsou.config.DynamicClientRegistrationController})
 * reaches {@code /oauth2/authorize}. The SPA then calls this endpoint to resolve the human-readable
 * client name and the actual requested scopes before rendering the approve/deny UI.
 *
 * <p>Lives under {@code /api/**} — cookie/bearer authenticated exactly like every other
 * first-party API route (no explicit {@code permitAll}; {@code SecurityConfig}'s
 * {@code anyRequest().authenticated()} already covers it), so an unauthenticated caller gets the
 * usual 401 and never sees another user's pending-consent details.
 */
@RestController
public class OAuthConsentController {

    private final RegisteredClientRepository registeredClientRepository;

    public OAuthConsentController(RegisteredClientRepository registeredClientRepository) {
        this.registeredClientRepository = registeredClientRepository;
    }

    @GetMapping("/api/oauth2/consent-info")
    public ConsentInfoResponse consentInfo(
        @RequestParam("client_id") String clientId,
        @RequestParam(value = "scope", required = false) String scope,
        @RequestParam(value = "state", required = false) String state
    ) {
        RegisteredClient client = registeredClientRepository.findByClientId(clientId);
        if (client == null) {
            throw new ResourceNotFoundException("Unknown OAuth client");
        }
        List<String> requestedScopes = resolveRequestedScopes(scope, client.getScopes());
        return new ConsentInfoResponse(client.getClientName(), requestedScopes, state);
    }

    /**
     * The {@code scope} query param is attacker-observable/-modifiable browser state, not a
     * trusted server value, even though Spring AS is the one that put it there in the normal
     * flow — so it is filtered down to the registered client's own scopes (themselves already
     * validated ⊆ {@link com.picsou.mcp.Scopes#ALL} at DCR time) rather than echoed verbatim.
     */
    private List<String> resolveRequestedScopes(String scope, Set<String> registeredScopes) {
        if (scope == null || scope.isBlank()) {
            return List.of();
        }
        Set<String> requested = new LinkedHashSet<>(List.of(scope.trim().split("\\s+")));
        requested.retainAll(registeredScopes);
        return List.copyOf(requested);
    }
}
