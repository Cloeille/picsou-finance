package com.picsou.service;

import com.picsou.dto.SetupStatusResponse;
import com.picsou.config.SsoProperties;
import com.picsou.model.UserIdentity;
import com.picsou.model.UserRole;
import com.picsou.repository.UserIdentityRepository;
import com.picsou.service.sso.SsoIdentity;
import com.picsou.model.AppSetting;
import com.picsou.model.AppUser;
import com.picsou.model.FamilyMember;
import com.picsou.model.SetupState;
import com.picsou.repository.AppSettingRepository;
import com.picsou.repository.AppUserRepository;
import com.picsou.repository.FamilyMemberRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.Set;
import java.time.Clock;
import java.time.Instant;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SetupServiceTest {

    @Mock AppSettingRepository settingRepository;
    @Mock AppUserRepository userRepository;
    @Mock FamilyMemberRepository memberRepository;
    @Mock PasswordEncoder passwordEncoder;
    @Mock UserIdentityRepository identityRepository;
    @Mock Clock clock;
    @Spy SsoProperties ssoProperties = new SsoProperties();

    @InjectMocks SetupService setupService;

    private static SsoIdentity aliceIdentity() {
        return new SsoIdentity("https://idp.test", "alice-sub", "alice@idp.test", "Alice", "alice", null,
            Optional.of(Set.of("picsou-admin")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://idp.test", "not-a-url"})
    void status_reportsRawAdminBootstrapEvenWhenIssuerIsInvalid(String issuer) {
        ssoProperties.setIssuerUri(issuer);
        ssoProperties.setClientId("picsou");
        ssoProperties.setAdminGroup("picsou-admin");
        ssoProperties.setProviderName("My IdP");

        var sso = setupService.getStatus().sso();

        assertThat(sso.enabled()).isEqualTo(issuer.equals("https://idp.test"));
        assertThat(sso.providerName()).isEqualTo("My IdP");
        assertThat(sso.adminBootstrap()).isTrue();
    }

    @Test
    void seedSsoAdmin_createsPasswordlessAdminAndLoggedInIdentity() {
        Instant now = Instant.parse("2026-11-03T10:00:00Z");
        when(clock.instant()).thenReturn(now);
        when(settingRepository.compareAndSet(SetupService.KEY_SETUP_STATE, "PENDING_ADMIN", "IN_PROGRESS"))
            .thenReturn(1);

        AppUser user = setupService.seedSsoAdmin(aliceIdentity(), "alice", "Alice");

        var member = ArgumentCaptor.forClass(FamilyMember.class);
        verify(memberRepository).save(member.capture());
        assertThat(member.getValue().getDisplayName()).isEqualTo("Alice");
        assertThat(member.getValue().isManaged()).isFalse();
        verify(userRepository).save(user);
        assertThat(user.getMember()).isSameAs(member.getValue());
        assertThat(user.getUsername()).isEqualTo("alice");
        assertThat(user.getPasswordHash()).isEmpty();
        assertThat(user.getRole()).isEqualTo(UserRole.ADMIN);
        assertThat(user.isActivated()).isTrue();
        assertThat(user.isAcknowledgedWarning()).isTrue();
        var identity = ArgumentCaptor.forClass(UserIdentity.class);
        verify(identityRepository).save(identity.capture());
        assertThat(identity.getValue().getUser()).isSameAs(user);
        assertThat(identity.getValue().getIssuer()).isEqualTo("https://idp.test");
        assertThat(identity.getValue().getSubject()).isEqualTo("alice-sub");
        assertThat(identity.getValue().getEmail()).isEqualTo("alice@idp.test");
        assertThat(identity.getValue().getDisplayName()).isEqualTo("Alice");
        assertThat(identity.getValue().getLastLoginAt()).isEqualTo(now);
    }

    @Test
    void seedSsoAdmin_refusesExistingUsernameWithoutReturningExistingAdmin() {
        // A second IdP subject deriving the same username must not receive the existing account.
        org.mockito.Mockito.lenient().when(userRepository.existsByUsername("alice")).thenReturn(true);
        org.mockito.Mockito.lenient().when(userRepository.findByUsernameWithMember("alice"))
            .thenReturn(Optional.of(AppUser.builder().username("alice").role(UserRole.ADMIN).build()));
        when(userRepository.count()).thenReturn(1L);

        assertThatThrownBy(() -> setupService.seedSsoAdmin(aliceIdentity(), "alice", "Alice"))
            .isInstanceOf(IllegalStateException.class);

        org.mockito.Mockito.verifyNoInteractions(memberRepository, identityRepository);
        verify(userRepository, never()).save(any());
    }

    @Test
    void seedSsoAdmin_refusesCompleteSetupBeforeWriting() {
        when(settingRepository.findByKey(SetupService.KEY_SETUP_STATE))
            .thenReturn(Optional.of(AppSetting.builder().value("COMPLETE").build()));

        assertThatThrownBy(() -> setupService.seedSsoAdmin(aliceIdentity(), "alice", "Alice"))
            .isInstanceOf(IllegalStateException.class);

        org.mockito.Mockito.verifyNoInteractions(memberRepository, identityRepository);
        verify(userRepository, never()).save(any());
    }

    @Test
    void seedSsoAdmin_refusesLostBootstrapClaimBeforeWriting() {
        when(settingRepository.compareAndSet(SetupService.KEY_SETUP_STATE, "PENDING_ADMIN", "IN_PROGRESS"))
            .thenReturn(0);

        assertThatThrownBy(() -> setupService.seedSsoAdmin(aliceIdentity(), "alice", "Alice"))
            .isInstanceOf(IllegalStateException.class);

        org.mockito.Mockito.verifyNoInteractions(memberRepository, identityRepository);
        verify(userRepository, never()).save(any());
    }

    @Test
    void status_defaultsToPendingAdmin_whenNoSettingExists() {
        when(settingRepository.findByKey(SetupService.KEY_SETUP_STATE)).thenReturn(Optional.empty());
        when(settingRepository.findByKey(org.mockito.ArgumentMatchers.startsWith("integration.")))
            .thenReturn(Optional.empty());

        SetupStatusResponse response = setupService.getStatus();

        assertThat(response.state()).isEqualTo(SetupState.PENDING_ADMIN);
        assertThat(response.needsSetup()).isTrue();
        assertThat(response.integrations()).containsKeys("enablebanking", "boursobank", "boursedirect", "traderepublic", "finary", "crypto");
        assertThat(response.integrations().values()).allMatch(b -> !b);
    }

    @Test
    void status_returnsComplete_whenSettingIsComplete() {
        AppSetting state = AppSetting.builder().key(SetupService.KEY_SETUP_STATE).value("COMPLETE").build();
        when(settingRepository.findByKey(SetupService.KEY_SETUP_STATE)).thenReturn(Optional.of(state));
        when(settingRepository.findByKey(org.mockito.ArgumentMatchers.startsWith("integration.")))
            .thenReturn(Optional.empty());

        SetupStatusResponse response = setupService.getStatus();

        assertThat(response.state()).isEqualTo(SetupState.COMPLETE);
        assertThat(response.needsSetup()).isFalse();
    }

    @Test
    void seedAdmin_createsFamilyMemberAndAppUser_andClaimsInProgressState() {
        when(userRepository.existsByUsername("admin")).thenReturn(false);
        when(settingRepository.findByKey(SetupService.KEY_SETUP_STATE)).thenReturn(Optional.empty());
        when(userRepository.count()).thenReturn(0L);
        when(settingRepository.compareAndSet(SetupService.KEY_SETUP_STATE,
            SetupState.PENDING_ADMIN.name(), SetupState.IN_PROGRESS.name())).thenReturn(1);

        String validBcryptHash = "$2a$12$abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZabc";

        setupService.seedAdmin("admin", validBcryptHash, "Alice", "#ff0000");

        ArgumentCaptor<FamilyMember> memberCaptor = ArgumentCaptor.forClass(FamilyMember.class);
        verify(memberRepository).save(memberCaptor.capture());
        assertThat(memberCaptor.getValue().getDisplayName()).isEqualTo("Alice");
        assertThat(memberCaptor.getValue().getAvatarColor()).isEqualTo("#ff0000");
        assertThat(memberCaptor.getValue().isManaged()).isFalse();

        ArgumentCaptor<AppUser> userCaptor = ArgumentCaptor.forClass(AppUser.class);
        verify(userRepository).save(userCaptor.capture());
        assertThat(userCaptor.getValue().getUsername()).isEqualTo("admin");
        assertThat(userCaptor.getValue().getPasswordHash()).isEqualTo(validBcryptHash);
        assertThat(userCaptor.getValue().isActivated()).isTrue();
    }

    @Test
    void seedAdmin_rejectsNonBcryptHash() {
        when(userRepository.existsByUsername(anyString())).thenReturn(false);
        when(settingRepository.findByKey(SetupService.KEY_SETUP_STATE)).thenReturn(Optional.empty());
        when(settingRepository.compareAndSet(anyString(), anyString(), anyString())).thenReturn(1);

        assertThatThrownBy(() -> setupService.seedAdmin("admin", "plain-password", "Alice", null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("bcrypt");

        verify(userRepository, never()).save(any());
        verify(memberRepository, never()).save(any());
    }

    @Test
    void seedAdmin_isIdempotent_whenUserAlreadyExists() {
        AppUser existing = AppUser.builder().username("admin").build();
        when(userRepository.existsByUsername("admin")).thenReturn(true);
        when(userRepository.findByUsernameWithMember("admin")).thenReturn(Optional.of(existing));

        AppUser result = setupService.seedAdmin("admin", "$2a$12$any", "Alice", null);

        assertThat(result).isSameAs(existing);
        verify(memberRepository, never()).save(any());
        verify(userRepository, never()).save(any());
    }

    @Test
    void seedAdmin_refuses_whenSetupAlreadyComplete() {
        AppSetting state = AppSetting.builder().key(SetupService.KEY_SETUP_STATE).value("COMPLETE").build();
        when(userRepository.existsByUsername(anyString())).thenReturn(false);
        when(settingRepository.findByKey(SetupService.KEY_SETUP_STATE)).thenReturn(Optional.of(state));

        assertThatThrownBy(() -> setupService.seedAdmin("admin", "$2a$12$any", null, null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("already complete");
    }

    /**
     * Closes the setup-wizard second-admin backdoor: while state is IN_PROGRESS (an
     * admin was seeded but /api/setup/complete has not been called yet), the wizard's
     * /admin endpoint is still unauthenticated. Without this guard a differently-named
     * account sails through — the same-username idempotency check doesn't catch it, and
     * the CAS only protects the PENDING_ADMIN -> IN_PROGRESS transition, not this one.
     */
    @Test
    void seedAdmin_refuses_whenAnyAccountAlreadyExists() {
        AppSetting state = AppSetting.builder().key(SetupService.KEY_SETUP_STATE).value("IN_PROGRESS").build();
        when(userRepository.existsByUsername("bob")).thenReturn(false);
        when(settingRepository.findByKey(SetupService.KEY_SETUP_STATE)).thenReturn(Optional.of(state));
        when(userRepository.count()).thenReturn(1L);

        assertThatThrownBy(() -> setupService.seedAdmin("bob", "$2a$12$any", "Bob", null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("already exists");

        verify(userRepository, never()).save(any());
        verify(memberRepository, never()).save(any());
    }

    @Test
    void markComplete_upsertsStateToComplete() {
        when(settingRepository.findByKey(SetupService.KEY_SETUP_STATE)).thenReturn(Optional.empty());

        setupService.markComplete();

        ArgumentCaptor<AppSetting> captor = ArgumentCaptor.forClass(AppSetting.class);
        verify(settingRepository).save(captor.capture());
        assertThat(captor.getValue().getKey()).isEqualTo(SetupService.KEY_SETUP_STATE);
        assertThat(captor.getValue().getValue()).isEqualTo("COMPLETE");
    }

    @Test
    void integrationKey_formatsConsistently() {
        assertThat(SetupService.integrationKey("enablebanking"))
            .isEqualTo("integration.enablebanking.enabled");
        assertThat(SetupService.integrationKey("crypto"))
            .isEqualTo("integration.crypto.enabled");
    }

    @Test
    void writeSecurity_persistsOriginsAsCsvAndSecureFlag() {
        when(settingRepository.findByKey(anyString())).thenReturn(Optional.empty());

        setupService.writeSecurity(
            java.util.List.of("https://picsou.example.com", "http://localhost:5173"),
            true
        );

        ArgumentCaptor<AppSetting> captor = ArgumentCaptor.forClass(AppSetting.class);
        verify(settingRepository, org.mockito.Mockito.times(2)).save(captor.capture());
        java.util.List<AppSetting> saved = captor.getAllValues();

        assertThat(saved.get(0).getKey()).isEqualTo(SetupService.KEY_CORS_ALLOWED_ORIGINS);
        assertThat(saved.get(0).getValue())
            .isEqualTo("https://picsou.example.com,http://localhost:5173");

        assertThat(saved.get(1).getKey()).isEqualTo(SetupService.KEY_SECURE_COOKIES);
        assertThat(saved.get(1).getValue()).isEqualTo("true");
    }

    @Test
    void writeSecurity_rejectsEmptyOriginList() {
        assertThatThrownBy(() -> setupService.writeSecurity(java.util.List.of(), false))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContainingAll("At", "least", "one");

        verify(settingRepository, never()).save(any());
    }

    @Test
    void writeSecurity_updatesExistingSetting_insteadOfInsertingDuplicate() {
        AppSetting existing = AppSetting.builder()
            .key(SetupService.KEY_CORS_ALLOWED_ORIGINS)
            .value("http://old")
            .build();
        when(settingRepository.findByKey(SetupService.KEY_CORS_ALLOWED_ORIGINS))
            .thenReturn(Optional.of(existing));
        when(settingRepository.findByKey(SetupService.KEY_SECURE_COOKIES))
            .thenReturn(Optional.empty());

        setupService.writeSecurity(java.util.List.of("http://new"), false);

        assertThat(existing.getValue()).isEqualTo("http://new");
    }
}
