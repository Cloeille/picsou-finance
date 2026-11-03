package com.picsou.service;

import com.picsou.config.SsoProperties;
import com.picsou.dto.LinkedIdentityResponse;
import com.picsou.dto.SignInMethodsResponse;
import com.picsou.dto.SsoInfoResponse;
import com.picsou.model.AppUser;
import com.picsou.model.UserIdentity;
import com.picsou.repository.UserIdentityRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SignInMethodsServiceTest {

    private static final String ISSUER = "https://idp.example.com/realm";

    @Mock private UserIdentityRepository identityRepository;
    @Mock private MfaService mfaService;

    private SsoProperties sso;
    private SignInMethodsService service;
    private AppUser user;

    @BeforeEach
    void setUp() {
        sso = new SsoProperties();
        service = new SignInMethodsService(identityRepository, mfaService, sso);
        user = AppUser.builder().id(7L).username("chloe").passwordHash("$2a$hash").build();
    }

    private static UserIdentity identity(long id, String issuer, Instant createdAt) {
        UserIdentity identity = UserIdentity.builder()
            .id(id).issuer(issuer).subject("sub-" + id).email("u" + id + "@example.com")
            .displayName("User " + id).lastLoginAt(Instant.parse("2026-02-01T00:00:00Z")).build();
        ReflectionTestUtils.setField(identity, "createdAt", createdAt);
        return identity;
    }

    @Test
    void hasPassword_isFalseForEmptyHashAndNull() {
        user.setPasswordHash("");
        assertThat(service.signInMethods(user).hasPassword()).isFalse();
        user.setPasswordHash(null);
        assertThat(service.signInMethods(user).hasPassword()).isFalse();
        user.setPasswordHash("  ");
        assertThat(service.signInMethods(user).hasPassword()).isFalse();
    }

    @Test
    void hasPasswordAndMfaFlags_reflectTheUser() {
        when(mfaService.isEnabled(user)).thenReturn(true);

        SignInMethodsResponse response = service.signInMethods(user);

        assertThat(response.hasPassword()).isTrue();
        assertThat(response.mfaEnabled()).isTrue();
    }

    @Test
    void identities_areListedForTheCurrentUserOnly_withCurrentIssuerFlag() {
        sso.setIssuerUri(ISSUER);
        sso.setClientId("picsou");
        when(identityRepository.findByUserIdOrderByCreatedAtAsc(7L)).thenReturn(List.of(
            identity(1L, ISSUER, Instant.parse("2026-01-01T00:00:00Z")),
            identity(2L, "https://old.example.com", Instant.parse("2026-01-02T00:00:00Z"))));

        List<LinkedIdentityResponse> identities = service.signInMethods(user).identities();

        assertThat(identities).extracting(LinkedIdentityResponse::id).containsExactly(1L, 2L);
        assertThat(identities).extracting(LinkedIdentityResponse::currentIssuer).containsExactly(true, false);
        assertThat(identities.get(0)).isEqualTo(new LinkedIdentityResponse(
            1L, "u1@example.com", "User 1",
            Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-02-01T00:00:00Z"), true));
        verify(identityRepository).findByUserIdOrderByCreatedAtAsc(7L);
        verify(identityRepository, never()).findAll();
    }

    @Test
    void ssoFlags_reflectTheProperties() {
        sso.setIssuerUri(ISSUER);
        sso.setClientId("picsou");
        sso.setProviderName("Authentik");
        sso.setAdminGroup("picsou-admin");

        SignInMethodsResponse response = service.signInMethods(user);

        assertThat(response.ssoEnabled()).isTrue();
        assertThat(response.providerName()).isEqualTo("Authentik");
        assertThat(response.adminGroupManaged()).isTrue();
        assertThat(service.ssoInfo()).isEqualTo(new SsoInfoResponse(true, "Authentik"));
    }

    @Test
    void ssoOff_defaults() {
        assertThat(service.ssoInfo()).isEqualTo(new SsoInfoResponse(false, "SSO"));
        SignInMethodsResponse response = service.signInMethods(user);
        assertThat(response.ssoEnabled()).isFalse();
        assertThat(response.adminGroupManaged()).isFalse();
        assertThat(response.identities()).isEmpty();
    }
}
