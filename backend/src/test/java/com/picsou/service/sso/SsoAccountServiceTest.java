package com.picsou.service.sso;

import com.picsou.config.SsoProperties;
import com.picsou.model.AppUser;
import com.picsou.model.FamilyMember;
import com.picsou.model.UserIdentity;
import com.picsou.model.UserRole;
import com.picsou.repository.AppUserRepository;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.UserIdentityRepository;
import com.picsou.service.SetupAuditService;
import com.picsou.service.SetupService;
import com.picsou.service.UsernameDeriver;
import com.picsou.service.sso.SsoAccountService.LoginOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SsoAccountServiceTest {

    private static final String ISSUER = "https://idp.example.com";
    private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");

    @Mock UserIdentityRepository userIdentityRepository;
    @Mock AppUserRepository appUserRepository;
    @Mock FamilyMemberRepository familyMemberRepository;
    @Mock SetupService setupService;
    @Mock SetupAuditService auditService;
    @Mock UsernameDeriver usernameDeriver;
    @Mock SsoProperties ssoProperties;

    private SsoAccountService service;

    @BeforeEach
    void setUp() {
        service = new SsoAccountService(userIdentityRepository, appUserRepository, familyMemberRepository,
            setupService, auditService, usernameDeriver, ssoProperties, Clock.fixed(NOW, ZoneOffset.UTC));
        lenient().when(ssoProperties.adminGroupManaged()).thenReturn(true);
        lenient().when(ssoProperties.adminGroup()).thenReturn("picsou-admins");
        lenient().when(setupService.isComplete()).thenReturn(true);
    }

    private static SsoIdentity identity(String preferredUsername, String email, Set<String> groups) {
        return new SsoIdentity(ISSUER, "sub-1", email, "Alice Martin", preferredUsername, null,
            groups == null ? Optional.empty() : Optional.of(groups));
    }

    private static AppUser user(long id, String username) {
        return AppUser.builder().id(id).username(username).passwordHash("x")
            .member(FamilyMember.builder().id(id).displayName(username).build()).build();
    }

    @Test
    void linkedIdentityResolvesAndTouchesLogin() {
        AppUser alice = user(1L, "alice");
        UserIdentity row = UserIdentity.builder().user(alice).issuer(ISSUER).subject("sub-1").build();
        when(userIdentityRepository.findByIssuerAndSubject(ISSUER, "sub-1")).thenReturn(Optional.of(row));
        when(appUserRepository.findByIdWithMember(1L)).thenReturn(Optional.of(alice));

        LoginOutcome outcome = service.resolveLogin(identity("a", "a@x.io", Set.of()), null);

        assertThat(outcome).isEqualTo(new LoginOutcome.Resolved(alice, false));
        assertThat(row.getLastLoginAt()).isEqualTo(NOW);
        assertThat(row.getEmail()).isEqualTo("a@x.io");
        assertThat(row.getDisplayName()).isEqualTo("Alice Martin");
    }

    @Test
    void groupMemberIsProvisionedAsPasswordlessAdmin() {
        when(userIdentityRepository.findByIssuerAndSubject(ISSUER, "sub-1")).thenReturn(Optional.empty());
        when(usernameDeriver.derive("alice")).thenReturn("alice");

        LoginOutcome outcome = service.resolveLogin(
            identity("alice@zitadel.example.com", "a@x.io", Set.of("picsou-admins")), null);

        LoginOutcome.Resolved resolved = (LoginOutcome.Resolved) outcome;
        assertThat(resolved.provisioned()).isTrue();
        AppUser created = resolved.user();
        assertThat(created.getUsername()).isEqualTo("alice");
        assertThat(created.getPasswordHash()).isEmpty();
        assertThat(created.getRole()).isEqualTo(UserRole.ADMIN);
        assertThat(created.isActivated()).isTrue();
        assertThat(created.isAcknowledgedWarning()).isTrue();
        assertThat(created.getMember().isManaged()).isFalse();
        assertThat(created.getMember().getDisplayName()).isEqualTo("Alice Martin");
        ArgumentCaptor<UserIdentity> saved = ArgumentCaptor.forClass(UserIdentity.class);
        verify(userIdentityRepository).save(saved.capture());
        assertThat(saved.getValue().getIssuer()).isEqualTo(ISSUER);
        assertThat(saved.getValue().getSubject()).isEqualTo("sub-1");
        assertThat(saved.getValue().getUser()).isSameAs(created);
        assertThat(saved.getValue().getLastLoginAt()).isEqualTo(NOW);
        verify(auditService).record(eq("sso.user.provisioned"), eq("alice"), any(), any());
    }

    @Test
    void nonMemberOfGroupIsRefused() {
        when(userIdentityRepository.findByIssuerAndSubject(ISSUER, "sub-1")).thenReturn(Optional.empty());

        assertThat(service.resolveLogin(identity("bob", "b@x.io", Set.of("other")), null))
            .isEqualTo(new LoginOutcome.Refused("NOT_LINKED"));
        assertThat(service.resolveLogin(identity("bob", "b@x.io", null), null))
            .isEqualTo(new LoginOutcome.Refused("NOT_LINKED"));
        verify(userIdentityRepository, never()).save(any());
    }

    @Test
    void incompleteSetupIsRefused() {
        when(userIdentityRepository.findByIssuerAndSubject(ISSUER, "sub-1")).thenReturn(Optional.empty());
        when(setupService.isComplete()).thenReturn(false);

        assertThat(service.resolveLogin(identity("bob", "b@x.io", Set.of("picsou-admins")), null))
            .isEqualTo(new LoginOutcome.Refused("NOT_LINKED"));
        verify(userIdentityRepository, never()).save(any());
    }

    @Test
    void unmanagedAdminGroupIsRefused() {
        when(userIdentityRepository.findByIssuerAndSubject(ISSUER, "sub-1")).thenReturn(Optional.empty());
        when(ssoProperties.adminGroupManaged()).thenReturn(false);

        assertThat(service.resolveLogin(identity("bob", "b@x.io", Set.of("picsou-admins")), null))
            .isEqualTo(new LoginOutcome.Refused("NOT_LINKED"));
        verify(userIdentityRepository, never()).save(any());
    }

    @Test
    void usernameUsesLocalPartOfPreferredUsername() {
        when(usernameDeriver.derive("alice")).thenReturn("alice");

        assertThat(service.usernameFor(identity("alice@zitadel.example.com", null, null))).isEqualTo("alice");
    }

    @Test
    void usernameFallsBackToDisplayNameAndTruncatesIt() {
        when(usernameDeriver.derive("Alice Martin")).thenReturn("alice.martin");
        SsoIdentity noUsername = identity(" ", null, null);

        assertThat(service.usernameFor(noUsername)).isEqualTo("alice.martin");

        SsoIdentity anonymous = new SsoIdentity(ISSUER, "s", null, null, null, null, Optional.empty());
        assertThat(service.displayNameFor(anonymous)).isEqualTo("SSO user");
        SsoIdentity longName = new SsoIdentity(ISSUER, "s", null, "x".repeat(150), null, null, Optional.empty());
        assertThat(service.displayNameFor(longName)).hasSize(100);
        SsoIdentity usernameOnly = new SsoIdentity(ISSUER, "s", null, " ", "bob", null, Optional.empty());
        assertThat(service.displayNameFor(usernameOnly)).isEqualTo("bob");
    }

    @Test
    void unlinkedIdentityNeverBindsToLocalUserWithSameUsernameOrEmail() {
        AppUser localAlice = user(7L, "alice");
        when(userIdentityRepository.findByIssuerAndSubject(ISSUER, "sub-1")).thenReturn(Optional.empty());
        lenient().when(appUserRepository.findByUsername("alice")).thenReturn(Optional.of(localAlice));

        assertThat(service.resolveLogin(identity("alice", "alice@home.example", Set.of()), null))
            .isEqualTo(new LoginOutcome.Refused("NOT_LINKED"));
        verify(userIdentityRepository, never()).save(any());

        when(usernameDeriver.derive("alice")).thenReturn("alice.2");
        LoginOutcome outcome = service.resolveLogin(
            identity("alice", "alice@home.example", Set.of("picsou-admins")), null);

        AppUser created = ((LoginOutcome.Resolved) outcome).user();
        assertThat(created).isNotSameAs(localAlice);
        assertThat(created.getUsername()).isEqualTo("alice.2");
        ArgumentCaptor<UserIdentity> saved = ArgumentCaptor.forClass(UserIdentity.class);
        verify(userIdentityRepository).save(saved.capture());
        assertThat(saved.getValue().getUser()).isNotSameAs(localAlice);
    }

    @Test
    void linkRejectsIdentityHeldByAnotherUser() {
        AppUser other = user(2L, "other");
        UserIdentity row = UserIdentity.builder().user(other).issuer(ISSUER).subject("sub-1").build();
        when(userIdentityRepository.findByIssuerAndSubject(ISSUER, "sub-1")).thenReturn(Optional.of(row));

        assertThatThrownBy(() -> service.link(1L, identity("a", null, null)))
            .isInstanceOfSatisfying(SsoFlowException.class, e -> assertThat(e.code()).isEqualTo("IDENTITY_IN_USE"));
        verify(userIdentityRepository, never()).save(any());
    }

    @Test
    void linkIsIdempotentForSameUser() {
        AppUser alice = user(1L, "alice");
        UserIdentity row = UserIdentity.builder().user(alice).issuer(ISSUER).subject("sub-1").build();
        when(userIdentityRepository.findByIssuerAndSubject(ISSUER, "sub-1")).thenReturn(Optional.of(row));
        when(appUserRepository.findByIdWithMember(1L)).thenReturn(Optional.of(alice));

        AppUser result = service.link(1L, identity("a", "new@x.io", null));

        assertThat(result).isSameAs(alice);
        assertThat(row.getEmail()).isEqualTo("new@x.io");
        assertThat(row.getLastLoginAt()).isNull();
        verify(userIdentityRepository, never()).save(any());
    }

    @Test
    void linkCreatesIdentityWithoutLoginTimestamp() {
        AppUser alice = user(1L, "alice");
        when(userIdentityRepository.findByIssuerAndSubject(ISSUER, "sub-1")).thenReturn(Optional.empty());
        when(appUserRepository.findByIdWithMember(1L)).thenReturn(Optional.of(alice));

        assertThat(service.link(1L, identity("a", "a@x.io", null))).isSameAs(alice);

        ArgumentCaptor<UserIdentity> saved = ArgumentCaptor.forClass(UserIdentity.class);
        verify(userIdentityRepository).save(saved.capture());
        assertThat(saved.getValue().getUser()).isSameAs(alice);
        assertThat(saved.getValue().getLastLoginAt()).isNull();
    }
}
