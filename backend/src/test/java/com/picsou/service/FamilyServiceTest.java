package com.picsou.service;

import com.picsou.dto.SharingSettingsRequest;
import com.picsou.model.Account;
import com.picsou.model.AccountType;
import com.picsou.model.AppUser;
import com.picsou.model.FamilyMember;
import com.picsou.model.Goal;
import com.picsou.model.SharedResource;
import com.picsou.model.SharingLevel;
import com.picsou.model.UserRole;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.AppUserRepository;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.GoalRepository;
import com.picsou.repository.SharedResourceRepository;
import com.picsou.repository.SharingSettingsRepository;
import com.picsou.repository.UserMfaRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class FamilyServiceTest {

    @Mock FamilyMemberRepository memberRepository;
    @Mock AppUserRepository userRepository;
    @Mock UserMfaRepository userMfaRepository;
    @Mock SharingSettingsRepository sharingSettingsRepository;
    @Mock SharedResourceRepository sharedResourceRepository;
    @Mock AccountRepository accountRepository;
    @Mock GoalRepository goalRepository;
    @Mock PasswordEncoder passwordEncoder;
    @Mock UsernameDeriver usernameDeriver;

    @InjectMocks FamilyService familyService;

    private FamilyMember member(String displayName) {
        return FamilyMember.builder()
            .id(3L)
            .displayName(displayName)
            .avatarColor("#6366f1")
            .managed(true)
            .build();
    }

    /** Captures the AppUser persisted by generateActivationToken and returns its username. */
    private String usernameFromActivation(String displayName) {
        when(memberRepository.findById(3L)).thenReturn(Optional.of(member(displayName)));
        when(userRepository.findByMemberId(3L)).thenReturn(Optional.empty());
        when(usernameDeriver.derive(displayName)).thenReturn("derived-user");

        familyService.generateActivationToken(3L);

        ArgumentCaptor<AppUser> captor = ArgumentCaptor.forClass(AppUser.class);
        verify(userRepository).save(captor.capture());
        return captor.getValue().getUsername();
    }

    @Test
    void generateActivationToken_usesDerivedUsername() {
        String username = usernameFromActivation("Alice");

        assertThat(username).isEqualTo("derived-user");
        verify(usernameDeriver).derive("Alice");
    }

    // ─── deleteMember ───────────────────────────────────────────────────

    private AppUser appUser(UserRole role) {
        return AppUser.builder().role(role).activated(true).build();
    }

    @Test
    void deleteMember_activatedMember_deletes() {
        FamilyMember target = member("Bob");
        when(memberRepository.findById(3L)).thenReturn(Optional.of(target));
        when(userRepository.findByMemberId(3L)).thenReturn(Optional.of(appUser(UserRole.MEMBER)));

        familyService.deleteMember(3L, 1L);

        verify(memberRepository).delete(target);
    }

    @Test
    void deleteMember_self_throwsForbidden() {
        when(memberRepository.findById(3L)).thenReturn(Optional.of(member("Self")));

        assertThatThrownBy(() -> familyService.deleteMember(3L, 3L))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("Cannot delete your own account");

        verify(memberRepository, never()).delete(any());
    }

    @Test
    void deleteMember_lastAdmin_throwsForbidden() {
        when(memberRepository.findById(3L)).thenReturn(Optional.of(member("TheAdmin")));
        when(userRepository.findByMemberId(3L)).thenReturn(Optional.of(appUser(UserRole.ADMIN)));
        when(userRepository.countByRole(UserRole.ADMIN)).thenReturn(1L);

        assertThatThrownBy(() -> familyService.deleteMember(3L, 1L))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("Cannot delete the last administrator");

        verify(memberRepository, never()).delete(any());
    }

    @Test
    void deleteMember_nonLastAdmin_deletes() {
        FamilyMember target = member("SecondAdmin");
        when(memberRepository.findById(3L)).thenReturn(Optional.of(target));
        when(userRepository.findByMemberId(3L)).thenReturn(Optional.of(appUser(UserRole.ADMIN)));
        when(userRepository.countByRole(UserRole.ADMIN)).thenReturn(2L);

        familyService.deleteMember(3L, 1L);

        verify(memberRepository).delete(target);
    }

    @Test
    void deleteMember_notFound_throws() {
        when(memberRepository.findById(3L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> familyService.deleteMember(3L, 1L))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("Member not found");
    }

    /**
     * Guards the regression that returned 500: when the target has a login, the
     * {@link AppUser} (loaded for the last-admin guard) must be deleted BEFORE the
     * member. Deleting the member first leaves a managed user pointing at a removed,
     * non-nullable {@code @OneToOne} → Hibernate throws TransientObjectException at flush.
     */
    @Test
    void deleteMember_withLogin_deletesUserBeforeMember() {
        FamilyMember target = member("Bob");
        AppUser user = appUser(UserRole.MEMBER);
        when(memberRepository.findById(3L)).thenReturn(Optional.of(target));
        when(userRepository.findByMemberId(3L)).thenReturn(Optional.of(user));

        familyService.deleteMember(3L, 1L);

        InOrder order = inOrder(userRepository, memberRepository);
        order.verify(userRepository).delete(user);
        order.verify(memberRepository).delete(target);
    }

    @Test
    void deleteMember_managedWithoutLogin_deletesOnlyMember() {
        FamilyMember target = member("Child");
        when(memberRepository.findById(3L)).thenReturn(Optional.of(target));
        when(userRepository.findByMemberId(3L)).thenReturn(Optional.empty());

        familyService.deleteMember(3L, 1L);

        verify(userRepository, never()).delete(any());
        verify(memberRepository).delete(target);
    }

    // ─── manual sharing ownership ─────────────────────────────────────────

    @Test
    void updateSharingSettings_manualAccount_rejectsForeignResourceId() {
        SharingSettingsRequest req = new SharingSettingsRequest(
            "ACCOUNT", SharingLevel.MANUAL, List.of(10L, 20L));
        Account owned = Account.builder()
            .id(10L)
            .name("LEP")
            .type(AccountType.LEP)
            .currency("EUR")
            .currentBalance(BigDecimal.ZERO)
            .build();
        when(accountRepository.findByIdInAndMemberId(List.of(10L, 20L), 3L))
            .thenReturn(List.of(owned));

        assertThatThrownBy(() -> familyService.updateSharingSettings(3L, req))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("One or more shared resource IDs not found");

        verify(sharingSettingsRepository, never()).save(any());
        verify(sharedResourceRepository, never()).deleteAllByOwnerMemberIdAndResourceType(any(), any());
        verify(sharedResourceRepository, never()).save(any());
        verify(sharedResourceRepository, never()).saveAll(any());
    }

    @Test
    void updateSharingSettings_manualGoal_rejectsForeignResourceId() {
        SharingSettingsRequest req = new SharingSettingsRequest(
            "GOAL", SharingLevel.MANUAL, List.of(10L, 20L));
        Goal owned = Goal.builder()
            .id(10L)
            .name("Trip")
            .targetAmount(new BigDecimal("1200"))
            .deadline(LocalDate.now().plusMonths(6))
            .accounts(List.of())
            .build();
        when(goalRepository.findByIdInAndMemberId(List.of(10L, 20L), 3L))
            .thenReturn(List.of(owned));

        assertThatThrownBy(() -> familyService.updateSharingSettings(3L, req))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("One or more shared resource IDs not found");

        verify(sharingSettingsRepository, never()).save(any());
        verify(sharedResourceRepository, never()).deleteAllByOwnerMemberIdAndResourceType(any(), any());
        verify(sharedResourceRepository, never()).save(any());
        verify(sharedResourceRepository, never()).saveAll(any());
    }

    @Test
    void updateSharingSettings_manualAccount_savesOnlyValidatedIds() {
        FamilyMember owner = member("Alice");
        SharingSettingsRequest req = new SharingSettingsRequest(
            "ACCOUNT", SharingLevel.MANUAL, List.of(10L, 20L, 10L));
        Account first = Account.builder()
            .id(10L)
            .name("Checking")
            .type(AccountType.CHECKING)
            .currency("EUR")
            .currentBalance(BigDecimal.ZERO)
            .build();
        Account second = Account.builder()
            .id(20L)
            .name("Savings")
            .type(AccountType.SAVINGS)
            .currency("EUR")
            .currentBalance(BigDecimal.ZERO)
            .build();
        when(accountRepository.findByIdInAndMemberId(List.of(10L, 20L), 3L))
            .thenReturn(List.of(first, second));
        when(sharingSettingsRepository.findByMemberIdAndResourceType(3L, "ACCOUNT"))
            .thenReturn(Optional.empty());
        when(memberRepository.findById(3L)).thenReturn(Optional.of(owner));

        familyService.updateSharingSettings(3L, req);

        verify(sharingSettingsRepository).save(any());
        verify(sharedResourceRepository).deleteAllByOwnerMemberIdAndResourceType(3L, "ACCOUNT");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SharedResource>> captor = ArgumentCaptor.forClass(List.class);
        verify(sharedResourceRepository).saveAll(captor.capture());
        assertThat(captor.getValue())
            .extracting(SharedResource::getResourceId)
            .containsExactly(10L, 20L);
    }

    @Test
    void updateSharingSettings_rejectsUnsupportedResourceType() {
        SharingSettingsRequest req = new SharingSettingsRequest(
            "TRANSACTION", SharingLevel.MANUAL, List.of(10L));

        assertThatThrownBy(() -> familyService.updateSharingSettings(3L, req))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unsupported resource type");

        verify(sharingSettingsRepository, never()).save(any());
        verify(sharedResourceRepository, never()).deleteAllByOwnerMemberIdAndResourceType(any(), any());
        verify(sharedResourceRepository, never()).save(any());
        verify(sharedResourceRepository, never()).saveAll(any());
    }
}
