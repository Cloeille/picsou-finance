package com.picsou.service;

import com.picsou.config.AuthCookieWriter;
import com.picsou.config.SsoProperties;
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
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The last-sign-in-method guard must read current state while holding the user's row lock. */
@ExtendWith(MockitoExtension.class)
class SignInMethodsLockTest {
    private static final String ISSUER = "https://idp.example.com";
    @Mock UserIdentityRepository identities;
    @Mock AppUserRepository users;
    @Mock ReAuthService reauth;
    @Mock MfaService mfa;
    @Mock PasswordEncoder encoder;
    @Mock PersistentSessionService sessions;
    @Mock SessionIssuer issuer;
    @Mock AuthCookieWriter cookies;
    @Mock SetupAuditService audit;
    private final MockHttpServletRequest request = new MockHttpServletRequest();
    private final AppUser caller = AppUser.builder().id(7L).passwordHash("stale-password").build();
    private final AppUser locked = AppUser.builder().id(7L).passwordHash("").build();
    private SignInMethodsService service;

    @BeforeEach
    void setUp() {
        SsoProperties properties = new SsoProperties();
        properties.setIssuerUri(ISSUER);
        properties.setClientId("picsou");
        service = new SignInMethodsService(identities, mfa, properties, reauth, users,
            encoder, sessions, issuer, cookies, audit);
        lenient().when(users.findByIdForUpdate(7L)).thenReturn(Optional.of(locked));
    }

    @Test
    void unlinkIdentity_checksFreshPasswordAndIdentityCountUnderLock() {
        UserIdentity identity = UserIdentity.builder().id(1L).issuer(ISSUER).build();
        when(identities.findByIdAndUserId(1L, 7L)).thenReturn(Optional.of(identity));
        lenient().when(identities.countByUserIdAndIssuer(7L, ISSUER)).thenReturn(1L);

        assertThatThrownBy(() -> service.unlinkIdentity(caller, 1L, request))
            .isInstanceOfSatisfying(SignInMethodException.class,
                ex -> assertThat(ex.getCode()).isEqualTo("LAST_SIGN_IN_METHOD"));

        InOrder order = inOrder(users, identities);
        order.verify(users).findByIdForUpdate(7L);
        order.verify(identities).findByIdAndUserId(1L, 7L);
        order.verify(identities).countByUserIdAndIssuer(7L, ISSUER);
        verify(identities, never()).delete(any());
    }

    @Test
    void setPassword_checksTheLockedRowNotTheCallersSnapshot() {
        AppUser passwordless = AppUser.builder().id(7L).passwordHash("").build();
        AppUser alreadySet = AppUser.builder().id(7L).passwordHash("set-by-a-concurrent-request").build();
        when(users.findByIdForUpdate(7L)).thenReturn(Optional.of(alreadySet));

        assertThatThrownBy(() -> service.setPassword(passwordless, "new-password", request, new MockHttpServletResponse()))
            .isInstanceOfSatisfying(SignInMethodException.class,
                ex -> assertThat(ex.getCode()).isEqualTo("PASSWORD_ALREADY_SET"));

        verify(users, never()).save(any());
    }

    @Test
    void removePassword_checksRemainingIdentityUnderLock() {
        assertThatThrownBy(() -> service.removePassword(caller, request, new MockHttpServletResponse()))
            .isInstanceOfSatisfying(SignInMethodException.class,
                ex -> assertThat(ex.getCode()).isEqualTo("IDENTITY_NOT_USED_YET"));

        InOrder order = inOrder(users, identities);
        order.verify(users).findByIdForUpdate(7L);
        order.verify(identities).existsByUserIdAndIssuerAndLastLoginAtIsNotNull(7L, ISSUER);
        verify(users, never()).save(any());
    }
}
