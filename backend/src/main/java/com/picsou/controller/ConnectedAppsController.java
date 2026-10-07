package com.picsou.controller;

import com.picsou.dto.ConnectedAppResponse;
import com.picsou.exception.ResourceNotFoundException;
import com.picsou.service.UserContext;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.sql.Timestamp;
import java.util.List;

/**
 * Self-service management of the caller's own OAuth2 connected apps — the remote-MCP clients
 * (e.g. claude.ai) they have granted consent to via the DCR + authorize + consent flow (Tasks
 * 8/10/11). Lives under {@code /api/**}, so it is cookie-authenticated and structurally
 * unreachable by a {@code psk_} key or an MCP JWT — {@code AccessKeyAuthFilter} only authenticates
 * {@code /mcp/**} (Property A). Mirrors {@link AccessKeyController}'s self-service shape: every
 * read/revoke is scoped to the caller's own {@code oauth2_authorization} rows (filtered by
 * {@code principal_name}), so one user can never see or revoke another's connected apps.
 *
 * <p>Reads go through a focused {@link JdbcTemplate} query joining {@code oauth2_authorization} to
 * {@code oauth2_registered_client} (for the human-readable client name) — the framework's
 * {@link OAuth2AuthorizationService} has no "find all by principal" method, only lookups by id or
 * token. Revocation goes through {@link OAuth2AuthorizationService#remove(OAuth2Authorization)} so
 * it stays the single framework-sanctioned deletion path (in case Spring AS ever attaches
 * additional cleanup to it), after confirming ownership.
 */
@RestController
@RequestMapping("/api/connected-apps")
public class ConnectedAppsController {

    private static final String LIST_SQL = """
        SELECT a.id AS id, c.client_name AS client_name, a.authorized_scopes AS scopes,
               a.access_token_issued_at AS issued_at
        FROM oauth2_authorization a
        JOIN oauth2_registered_client c ON c.id = a.registered_client_id
        WHERE a.principal_name = ? AND a.access_token_value IS NOT NULL
        ORDER BY a.access_token_issued_at DESC
        """;

    private final JdbcTemplate jdbcTemplate;
    private final OAuth2AuthorizationService authorizationService;
    private final UserContext userContext;

    public ConnectedAppsController(
        JdbcTemplate jdbcTemplate,
        OAuth2AuthorizationService authorizationService,
        UserContext userContext
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.authorizationService = authorizationService;
        this.userContext = userContext;
    }

    @GetMapping
    public List<ConnectedAppResponse> list() {
        String principalName = userContext.currentUser().getUsername();
        return jdbcTemplate.query(LIST_SQL, (rs, rowNum) -> {
            String scopesRaw = rs.getString("scopes");
            List<String> scopes = StringUtils.hasText(scopesRaw)
                ? List.copyOf(StringUtils.commaDelimitedListToSet(scopesRaw))
                : List.of();
            Timestamp issuedAt = rs.getTimestamp("issued_at");
            return new ConnectedAppResponse(
                rs.getString("id"),
                rs.getString("client_name"),
                scopes,
                issuedAt == null ? null : issuedAt.toInstant(),
                // The Spring AS JDBC schema (V54) does not track a last-used timestamp.
                null
            );
        }, principalName);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> revoke(@PathVariable String id) {
        String principalName = userContext.currentUser().getUsername();
        OAuth2Authorization authorization = authorizationService.findById(id);
        if (authorization == null || !authorization.getPrincipalName().equals(principalName)) {
            throw new ResourceNotFoundException("Connected app not found");
        }
        authorizationService.remove(authorization);
        return ResponseEntity.noContent().build();
    }
}
