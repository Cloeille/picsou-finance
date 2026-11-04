package com.picsou.service;

import com.picsou.config.AuthCookieWriter;
import com.picsou.config.JwtUtil;
import com.picsou.config.SecureCookieProvider;
import com.picsou.config.SsoProperties;
import com.picsou.dto.LinkedIdentityResponse;
import com.picsou.dto.SignInMethodsResponse;
import com.picsou.dto.SsoInfoResponse;
import com.picsou.exception.ResourceNotFoundException;
import com.picsou.exception.SignInMethodException;
import com.picsou.model.AppUser;
import com.picsou.model.UserIdentity;
import com.picsou.repository.AppUserRepository;
import com.picsou.repository.UserIdentityRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SignInMethodsServiceTest {

    private static final String ISSUER = "https://idp.example.com/realm";

    @Mock private UserIdentityRepository identityRepository;
    @Mock private MfaService mfaService;
    @Mock private ReAuthService reAuthService;
    @Mock private AppUserRepository userRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private PersistentSessionService persistentSessionService;
    @Mock private SessionIssuer sessionIssuer;
    @Mock private SetupAuditService auditService;
    @Mock private SecureCookieProvider secureCookieProvider;

    private final MockHttpServletRequest request = new MockHttpServletRequest();
    private final MockHttpServletResponse response = new MockHttpServletResponse();

    private SsoProperties sso;
    private SignInMethodsService service;
    private AppUser user;

    @BeforeEach
    void setUp() {
        sso = new SsoProperties();
        AuthCookieWriter cookieWriter = new AuthCookieWriter(secureCookieProvider, new JwtUtil("0123456789abcdef0123456789abcdef-test", 15, 7, 5));
        service = new SignInMethodsService(identityRepository, mfaService, sso, reAuthService,
            userRepository, passwordEncoder, persistentSessionService, sessionIssuer, cookieWriter, auditService);
        user = AppUser.builder().id(7L).username("chloe").passwordHash("$2a$hash").build();
        org.mockito.Mockito.lenient().when(userRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(user));
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

    private void ssoOn() {
        sso.setIssuerUri(ISSUER);
        sso.setClientId("picsou");
    }

    private static void assertRefusal(Runnable call, HttpStatus status, String code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(SignInMethodException.class, ex -> {
            assertThat(ex.getStatus()).isEqualTo(status);
            assertThat(ex.getCode()).isEqualTo(code);
        });
    }

    private void assertPersistentCookieCleared() {
        assertThat(response.getHeaders("Set-Cookie")).anySatisfy(
            c -> assertThat(c).startsWith("persistent_token=").contains("Max-Age=0"));
    }

    // --- DELETE /api/auth/password ---

    @Test
    void removePassword_withoutProof_isRefused() {
        doThrow(new ReAuthService.ReAuthFailedException("x")).when(reAuthService).requireProof(user, request);

        assertThatThrownBy(() -> service.removePassword(user, request, response))
            .isInstanceOf(ReAuthService.ReAuthFailedException.class);
        verify(userRepository, never()).save(any());
    }

    @Test
    void removePassword_ssoDisabled_is409SsoDisabled() {
        assertRefusal(() -> service.removePassword(user, request, response), HttpStatus.CONFLICT, "SSO_DISABLED");
        verify(userRepository, never()).save(any());
    }

    @Test
    void removePassword_withoutACurrentIssuerLogin_is409IdentityNotUsedYet() {
        ssoOn();
        when(identityRepository.existsByUserIdAndIssuerAndLastLoginAtIsNotNull(7L, ISSUER)).thenReturn(false);

        assertRefusal(() -> service.removePassword(user, request, response), HttpStatus.CONFLICT, "IDENTITY_NOT_USED_YET");
        verify(userRepository, never()).save(any());
    }

    @Test
    void removePassword_alreadyWithoutPassword_isANoOp() {
        ssoOn();
        user.setPasswordHash("");
        when(identityRepository.existsByUserIdAndIssuerAndLastLoginAtIsNotNull(7L, ISSUER)).thenReturn(true);

        service.removePassword(user, request, response);

        verify(userRepository, never()).save(any());
        verify(persistentSessionService, never()).revokeAllForUser(any());
        assertThat(user.getTokenVersion()).isZero();
    }

    @Test
    void removePassword_clearsHashDisablesMfaBumpsTvRevokesReissuesAndClearsPersistent() {
        ssoOn();
        Instant lastLogin = Instant.parse("2026-03-01T10:00:00Z");
        when(identityRepository.existsByUserIdAndIssuerAndLastLoginAtIsNotNull(7L, ISSUER)).thenReturn(true);
        when(identityRepository.findLatestLoginAt(7L, ISSUER)).thenReturn(lastLogin);
        when(mfaService.isEnabled(user)).thenReturn(true);

        service.removePassword(user, request, response);

        assertThat(user.getPasswordHash()).isEmpty();
        assertThat(user.getTokenVersion()).isEqualTo(1L);
        InOrder order = inOrder(mfaService, userRepository, persistentSessionService, sessionIssuer);
        order.verify(mfaService).disable(user);
        order.verify(userRepository).save(user);
        order.verify(persistentSessionService).revokeAllForUser(7L);
        order.verify(sessionIssuer).issue(user, false, false, lastLogin, request, response);
        assertPersistentCookieCleared();
        verify(auditService).record(eq("auth.password.removed"), eq("chloe"), eq(request), any());
    }

    @Test
    void removePassword_withoutMfa_doesNotTouchMfa() {
        ssoOn();
        when(identityRepository.existsByUserIdAndIssuerAndLastLoginAtIsNotNull(7L, ISSUER)).thenReturn(true);

        service.removePassword(user, request, response);

        verify(mfaService, never()).disable(any());
    }

    // --- PUT /api/auth/password ---

    @Test
    void setPassword_withoutProof_isRefused() {
        doThrow(new ReAuthService.ReAuthFailedException("x")).when(reAuthService).requireProof(user, request);

        assertThatThrownBy(() -> service.setPassword(user, "new-password-1", request, response))
            .isInstanceOf(ReAuthService.ReAuthFailedException.class);
        verify(userRepository, never()).save(any());
    }

    @Test
    void setPassword_whenOneExists_is409PasswordAlreadySet() {
        assertRefusal(() -> service.setPassword(user, "new-password-1", request, response),
            HttpStatus.CONFLICT, "PASSWORD_ALREADY_SET");
        verify(userRepository, never()).save(any());
    }

    @Test
    void setPassword_hashesBumpsTvRevokesReissuesWithCurrentSsoAtAndClearsPersistent() {
        user.setPasswordHash("");
        Instant ssoAt = Instant.parse("2026-03-01T10:00:00Z");
        when(passwordEncoder.encode("new-password-1")).thenReturn("$2a$new");
        when(sessionIssuer.currentSsoAt(request)).thenReturn(ssoAt);

        service.setPassword(user, "new-password-1", request, response);

        assertThat(user.getPasswordHash()).isEqualTo("$2a$new");
        assertThat(user.getTokenVersion()).isEqualTo(1L);
        InOrder order = inOrder(userRepository, persistentSessionService, sessionIssuer);
        order.verify(userRepository).save(user);
        order.verify(persistentSessionService).revokeAllForUser(7L);
        order.verify(sessionIssuer).issue(user, false, false, ssoAt, request, response);
        assertPersistentCookieCleared();
        verify(auditService).record(eq("auth.password.set"), eq("chloe"), eq(request), any());
    }

    // --- DELETE /api/auth/identities/{id} ---

    @Test
    void unlinkIdentity_withoutProof_isRefused() {
        doThrow(new ReAuthService.ReAuthFailedException("x")).when(reAuthService).requireProof(user, request);

        assertThatThrownBy(() -> service.unlinkIdentity(user, 1L, request))
            .isInstanceOf(ReAuthService.ReAuthFailedException.class);
        verify(identityRepository, never()).delete(any());
    }

    @Test
    void unlinkIdentity_ofAnotherUser_is404() {
        when(identityRepository.findByIdAndUserId(5L, 7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.unlinkIdentity(user, 5L, request))
            .isInstanceOf(ResourceNotFoundException.class);
        verify(identityRepository, never()).delete(any());
    }

    @Test
    void unlinkIdentity_lastCurrentIssuerIdentityWithoutPassword_is409() {
        ssoOn();
        user.setPasswordHash("");
        UserIdentity current = identity(1L, ISSUER, Instant.now());
        when(identityRepository.findByIdAndUserId(1L, 7L)).thenReturn(Optional.of(current));
        when(identityRepository.countByUserIdAndIssuer(7L, ISSUER)).thenReturn(1L);

        assertRefusal(() -> service.unlinkIdentity(user, 1L, request), HttpStatus.CONFLICT, "LAST_SIGN_IN_METHOD");
        verify(identityRepository, never()).delete(any());
    }

    @Test
    void unlinkIdentity_withPassword_deletesWithoutBumpingTvAndAudits() {
        UserIdentity current = identity(1L, ISSUER, Instant.now());
        when(identityRepository.findByIdAndUserId(1L, 7L)).thenReturn(Optional.of(current));

        service.unlinkIdentity(user, 1L, request);

        verify(identityRepository).delete(current);
        assertThat(user.getTokenVersion()).isZero();
        verify(userRepository, never()).save(any());
        verify(auditService).record(eq("sso.identity.unlinked"), eq("chloe"), eq(request), any());
    }

    @Test
    void unlinkIdentity_passwordlessWithAnotherCurrentIssuerIdentity_isAllowed() {
        ssoOn();
        user.setPasswordHash("");
        UserIdentity current = identity(1L, ISSUER, Instant.now());
        when(identityRepository.findByIdAndUserId(1L, 7L)).thenReturn(Optional.of(current));
        when(identityRepository.countByUserIdAndIssuer(7L, ISSUER)).thenReturn(2L);

        service.unlinkIdentity(user, 1L, request);

        verify(identityRepository).delete(current);
    }

    @Test
    void unlinkIdentity_passwordlessOldIssuerIdentity_withACurrentOneRemaining_isAllowed() {
        ssoOn();
        user.setPasswordHash("");
        UserIdentity old = identity(2L, "https://old.example.com", Instant.now());
        when(identityRepository.findByIdAndUserId(2L, 7L)).thenReturn(Optional.of(old));
        when(identityRepository.countByUserIdAndIssuer(7L, ISSUER)).thenReturn(1L);

        service.unlinkIdentity(user, 2L, request);

        verify(identityRepository).delete(old);
    }
}
