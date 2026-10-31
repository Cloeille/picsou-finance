package com.picsou.mcp.tools;

import com.picsou.config.AccessKeyAuthentication;
import com.picsou.config.OAuthClientProperties;
import com.picsou.mcp.AccessKeyService;
import com.picsou.model.AccessKey;
import com.picsou.model.AppUser;
import com.picsou.model.FamilyMember;
import com.picsou.service.MfaService;
import com.picsou.service.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Both OAuth2 tools are read-only reflections of state that already exists: static server metadata
 * for {@link OAuth2Tools#getOAuth2Configuration()}, and the calling access-key's own row for
 * {@link OAuth2Tools#getOAuth2SessionStatus()}. Member/session isolation is proven by asserting the
 * lookup is always keyed on the current {@link AccessKeyAuthentication}'s {@code keyId}, never an
 * arbitrary one.
 */
@ExtendWith(MockitoExtension.class)
class OAuth2ToolsTest {

    private static final long MID = 7L;

    @Mock AuthorizationServerSettings settings;
    @Mock OAuthClientProperties clientProperties;
    @Mock AccessKeyService accessKeyService;
    @Mock MfaService mfaService;
    @Mock UserContext userContext;

    private OAuth2Tools tools;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        tools = new OAuth2Tools(settings, clientProperties, accessKeyService, mfaService, userContext);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void getOAuth2Configuration_returnsIssuerAndEndpointsAndPkceRequirement_noSecrets() {
        when(settings.getIssuer()).thenReturn("https://picsou.example.com");
        when(settings.getAuthorizationEndpoint()).thenReturn("/oauth2/authorize");
        when(settings.getTokenEndpoint()).thenReturn("/oauth2/token");
        when(settings.getJwkSetEndpoint()).thenReturn("/oauth2/jwks");
        when(clientProperties.getClientId()).thenReturn("picsou-ios");

        OAuth2Tools.OAuth2Configuration config = tools.getOAuth2Configuration();

        assertThat(config.issuer()).isEqualTo("https://picsou.example.com");
        assertThat(config.tokenEndpoint()).isEqualTo("/oauth2/token");
        assertThat(config.authorizationEndpoint()).isEqualTo("/oauth2/authorize");
        assertThat(config.jwkSetEndpoint()).isEqualTo("/oauth2/jwks");
        assertThat(config.clientId()).isEqualTo("picsou-ios");
        assertThat(config.pkceRequired()).isTrue();
        // No field on the record can carry a secret — it's a fixed, small shape.
        assertThat(config.toString()).doesNotContain("secret", "psk_");
    }

    @Test
    void getOAuth2SessionStatus_returnsCallingKeysOwnStatus() {
        grantAccessKey(42L);
        AccessKey ownKey = accessKey(42L, "claude.ai", Set.of("budget:categories-read"));
        AccessKey otherKey = accessKey(99L, "other device", Set.of("accounts:read"));
        when(userContext.currentMemberId()).thenReturn(MID);
        when(accessKeyService.list(MID)).thenReturn(List.of(otherKey, ownKey));
        AppUser owner = mockOwner();
        when(userContext.currentUser()).thenReturn(owner);
        when(mfaService.isEnabled(owner)).thenReturn(true);

        OAuth2Tools.OAuth2SessionStatus status = tools.getOAuth2SessionStatus();

        assertThat(status.keyId()).isEqualTo(42L);
        assertThat(status.keyName()).isEqualTo("claude.ai");
        assertThat(status.scopes()).containsExactly("budget:categories-read");
        assertThat(status.mfaEnabled()).isTrue();
        assertThat(status.source()).isEqualTo("access_key");
    }

    // ─── Must-fix: MCP-JWT sessions (AccessKeyAuthentication with a null keyId) ──────────────

    @Test
    void getOAuth2SessionStatus_returnsStatusBuiltFromTheAuthentication_forAnMcpJwtSession() {
        AppUser owner = mockOwner();
        SecurityContextHolder.getContext().setAuthentication(new AccessKeyAuthentication(
            owner,
            List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("accounts:read"),
                new org.springframework.security.core.authority.SimpleGrantedAuthority("goals:read")),
            null));
        when(userContext.currentUser()).thenReturn(owner);
        when(mfaService.isEnabled(owner)).thenReturn(false);

        OAuth2Tools.OAuth2SessionStatus status = tools.getOAuth2SessionStatus();

        assertThat(status.keyId()).isNull();
        assertThat(status.keyName()).isNull();
        assertThat(status.scopes()).containsExactlyInAnyOrder("accounts:read", "goals:read");
        assertThat(status.createdAt()).isNull();
        assertThat(status.lastUsedAt()).isNull();
        assertThat(status.expiresAt()).isNull();
        assertThat(status.mfaEnabled()).isFalse();
        assertThat(status.source()).isEqualTo("oauth2");
        // The null-keyId path never touches the AccessKey repository — there is no row to look up.
        org.mockito.Mockito.verifyNoInteractions(accessKeyService);
    }

    @Test
    void getOAuth2SessionStatus_neverReturnsAnotherKeysStatus() {
        grantAccessKey(42L);
        AccessKey otherKey = accessKey(99L, "someone else's key", Set.of("accounts:read"));
        when(userContext.currentMemberId()).thenReturn(MID);
        when(accessKeyService.list(MID)).thenReturn(List.of(otherKey));

        assertThatThrownBy(() -> tools.getOAuth2SessionStatus())
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void getOAuth2SessionStatus_requiresAnAccessKeySession() {
        SecurityContextHolder.getContext().setAuthentication(
            new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("cookie-user", null, List.of()));

        assertThatThrownBy(() -> tools.getOAuth2SessionStatus())
            .isInstanceOf(IllegalStateException.class);
    }

    private void grantAccessKey(long keyId) {
        SecurityContextHolder.getContext().setAuthentication(
            new AccessKeyAuthentication(mockOwner(), List.of(), keyId));
    }

    private AppUser mockOwner() {
        FamilyMember member = FamilyMember.builder().id(MID).build();
        return AppUser.builder().id(1L).username("chloe").member(member).build();
    }

    private AccessKey accessKey(long id, String name, Set<String> scopes) {
        return AccessKey.builder()
            .id(id)
            .name(name)
            .scopes(scopes)
            .keyPrefix("psk_test" + id)
            .keyHash("hash")
            .createdBy(1L)
            .lastUsedAt(Instant.parse("2026-07-01T00:00:00Z"))
            .build();
    }
}
