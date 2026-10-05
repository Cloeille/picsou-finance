package com.picsou.service;

import com.picsou.config.OAuthClientProperties;
import com.picsou.model.AppUser;
import com.picsou.model.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationCode;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

import java.security.Principal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NativeAppSessionServiceTest {

    private static final Instant T0 = Instant.now().truncatedTo(ChronoUnit.SECONDS).minusSeconds(3600);

    @Mock JdbcOperations jdbc;
    @Mock OAuth2AuthorizationService authorizationService;
    @Mock RegisteredClientRepository registeredClientRepository;

    NativeAppSessionService service;
    RegisteredClient iosClient;
    AppUser alice;

    @BeforeEach
    void setUp() {
        OAuthClientProperties props = new OAuthClientProperties();
        props.setClientId("picsou-ios");
        service = new NativeAppSessionService(jdbc, authorizationService, registeredClientRepository, props);
        iosClient = RegisteredClient.withId("client-row-1")
            .clientId("picsou-ios")
            .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri("picsou://callback")
            .build();
        alice = AppUser.builder().id(7L).username("alice").role(UserRole.MEMBER).activated(true).tokenVersion(2L).build();
        lenient().when(registeredClientRepository.findByClientId("picsou-ios")).thenReturn(iosClient);
    }

    @Test
    void listActive_keepsOnlyTheCallersLiveAuthorizations_newestFirst() {
        stubCandidates("older", "newer", "stale-tv", "other-user", "refresh-revoked");
        stub(authorization("older", alice, 2L, T0.plusSeconds(60), false));
        stub(authorization("newer", alice, 2L, T0.plusSeconds(600), false));
        stub(authorization("stale-tv", alice, 1L, T0.plusSeconds(900), false));
        stub(authorization("other-user", AppUser.builder().id(8L).username("alice").role(UserRole.MEMBER).build(),
            2L, T0.plusSeconds(900), false));
        stub(authorization("refresh-revoked", alice, 2L, T0.plusSeconds(900), true));

        List<NativeAppSessionService.NativeAppSession> sessions = service.listActive(alice);

        assertThat(sessions).extracting(NativeAppSessionService.NativeAppSession::id).containsExactly("newer", "older");
        assertThat(sessions.get(0).createdAt()).isEqualTo(T0);
        assertThat(sessions.get(0).lastUsedAt()).isEqualTo(T0.plusSeconds(600));
        assertThat(sessions.get(0).expiresAt()).isEqualTo(T0.plusSeconds(30 * 86400));
    }

    @Test
    void revoke_removesTheCallersAuthorization() {
        stubCandidates("mine");
        OAuth2Authorization mine = authorization("mine", alice, 2L, T0, false);
        stub(mine);

        assertThat(service.revoke("mine", alice)).isTrue();
        verify(authorizationService).remove(mine);
    }

    @Test
    void revoke_refusesAnIdOutsideTheCallersList() {
        stubCandidates("mine");
        stub(authorization("mine", alice, 2L, T0, false));

        assertThat(service.revoke("not-mine", alice)).isFalse();
        verify(authorizationService, never()).remove(any());
    }

    @Test
    void revokeAllExcept_sparesOnlyTheKeptAuthorization() {
        stubCandidates("keep", "drop");
        OAuth2Authorization keep = authorization("keep", alice, 2L, T0, false);
        OAuth2Authorization drop = authorization("drop", alice, 2L, T0, false);
        stub(keep);
        stub(drop);

        service.revokeAllExcept(alice, "keep");

        verify(authorizationService).remove(drop);
        verify(authorizationService, never()).remove(keep);
    }

    @Test
    void isAccessTokenActive_falseOnceTheRowIsGone() {
        when(authorizationService.findById("gone")).thenReturn(null);

        assertThat(service.isAccessTokenActive("gone")).isFalse();
    }

    @Test
    void isAccessTokenActive_trueForAPresentUninvalidatedToken() {
        when(authorizationService.findById("live")).thenReturn(authorization("live", alice, 2L, T0, false));

        assertThat(service.isAccessTokenActive("live")).isTrue();
    }

    private void stubCandidates(String... ids) {
        when(jdbc.queryForList(anyString(), eq(String.class), eq("client-row-1"), eq("alice"), any()))
            .thenReturn(List.of(ids));
    }

    private void stub(OAuth2Authorization authorization) {
        when(authorizationService.findById(authorization.getId())).thenReturn(authorization);
    }

    private OAuth2Authorization authorization(String id, AppUser owner, long capturedTokenVersion,
                                              Instant lastAccess, boolean refreshInvalidated) {
        AppUser snapshot = AppUser.builder().id(owner.getId()).username(owner.getUsername())
            .role(UserRole.MEMBER).tokenVersion(capturedTokenVersion).build();
        OAuth2Authorization.Builder builder = OAuth2Authorization.withRegisteredClient(iosClient)
            .id(id)
            .principalName(owner.getUsername())
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .attribute(Principal.class.getName(), new UsernamePasswordAuthenticationToken(snapshot, null, List.of()))
            .token(new OAuth2AuthorizationCode("code", T0, T0.plusSeconds(300)),
                metadata -> metadata.put(OAuth2Authorization.Token.INVALIDATED_METADATA_NAME, true))
            .accessToken(new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "at-" + id,
                lastAccess, lastAccess.plusSeconds(900), Set.of("read")));
        OAuth2RefreshToken refresh = new OAuth2RefreshToken("rt-" + id, T0, T0.plusSeconds(30 * 86400));
        if (refreshInvalidated) {
            builder.token(refresh, metadata -> metadata.put(OAuth2Authorization.Token.INVALIDATED_METADATA_NAME, true));
        } else {
            builder.refreshToken(refresh);
        }
        return builder.build();
    }
}
