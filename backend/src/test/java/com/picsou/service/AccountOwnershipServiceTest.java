package com.picsou.service;

import com.picsou.dto.OwnershipRequest;
import com.picsou.dto.OwnershipResponse;
import com.picsou.model.Account;
import com.picsou.model.AccountOwnership;
import com.picsou.model.AccountType;
import com.picsou.model.FamilyMember;
import com.picsou.repository.AccountOwnershipRepository;
import com.picsou.repository.FamilyMemberRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AccountOwnershipServiceTest {

    private static final FamilyMember ALICE = FamilyMember.builder().id(1L).displayName("Alice").build();
    private static final FamilyMember BOB = FamilyMember.builder().id(2L).displayName("Bob").build();

    @Mock AccountOwnershipRepository ownershipRepository;
    @Mock FamilyMemberRepository memberRepository;
    @Mock AccountAccessResolver accessResolver;

    @InjectMocks AccountOwnershipService service;

    private static Account account(AccountType type) {
        return Account.builder()
            .id(10L).name("Maison").type(type).currency("EUR")
            .currentBalance(new BigDecimal("400000")).color("#a855f7").member(ALICE)
            .build();
    }

    private static OwnershipRequest request(Object... pairs) {
        List<OwnershipRequest.Share> shares = new java.util.ArrayList<>();
        for (int i = 0; i < pairs.length; i += 2) {
            shares.add(new OwnershipRequest.Share((Long) pairs[i], new BigDecimal((String) pairs[i + 1])));
        }
        return new OwnershipRequest(shares);
    }

    @Test
    void replace_savesTheSplit() {
        Account house = account(AccountType.REAL_ESTATE);
        when(accessResolver.requireOwner(10L, 1L)).thenReturn(house);
        when(memberRepository.findById(1L)).thenReturn(Optional.of(ALICE));
        when(memberRepository.findById(2L)).thenReturn(Optional.of(BOB));
        when(ownershipRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        OwnershipResponse result = service.replace(10L, 1L, request(1L, "50", 2L, "50"));

        assertThat(result.totalAssigned()).isEqualByComparingTo("100");
        assertThat(result.unassigned()).isEqualByComparingTo("0");
        assertThat(result.shares()).hasSize(2);
        verify(ownershipRepository).deleteAllForAccount(10L);
    }

    @Test
    void replace_underHundredPercent_isAllowedAndReportsTheRemainder() {
        Account house = account(AccountType.REAL_ESTATE);
        when(accessResolver.requireOwner(10L, 1L)).thenReturn(house);
        when(memberRepository.findById(1L)).thenReturn(Optional.of(ALICE));
        when(ownershipRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        // Indivision with someone who does not use Picsou. The remainder belongs to nobody's
        // net worth, so it is surfaced rather than quietly folded into the owner's share.
        OwnershipResponse result = service.replace(10L, 1L, request(1L, "60"));

        assertThat(result.totalAssigned()).isEqualByComparingTo("60");
        assertThat(result.unassigned()).isEqualByComparingTo("40");
    }

    @Test
    void replace_overHundredPercent_isRejected() {
        Account house = account(AccountType.REAL_ESTATE);
        when(accessResolver.requireOwner(10L, 1L)).thenReturn(house);

        // Would conjure value out of nothing once every member's share is totalled.
        assertThatThrownBy(() -> service.replace(10L, 1L, request(1L, "60", 2L, "60")))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("more than 100%");
    }

    @Test
    void replace_withoutTheOwner_isRejected() {
        Account house = account(AccountType.REAL_ESTATE);
        when(accessResolver.requireOwner(10L, 1L)).thenReturn(house);

        // The owner stays responsible for the account; writing them out entirely is a
        // transfer, which means moving the account, not zeroing its owner.
        assertThatThrownBy(() -> service.replace(10L, 1L, request(2L, "100")))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("owner must keep a share");
    }

    @Test
    void replace_duplicateMember_isRejected() {
        Account house = account(AccountType.REAL_ESTATE);
        when(accessResolver.requireOwner(10L, 1L)).thenReturn(house);

        assertThatThrownBy(() -> service.replace(10L, 1L, request(1L, "50", 1L, "50")))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("twice");
    }

    @Test
    void replace_onACheckingAccount_isRejected() {
        Account checking = account(AccountType.CHECKING);
        when(accessResolver.requireOwner(10L, 1L)).thenReturn(checking);

        // Splitting a joint current account would also have to split its transactions and
        // its sync; refusing beats half-supporting it.
        assertThatThrownBy(() -> service.replace(10L, 1L, request(1L, "50", 2L, "50")))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("real estate, SCPI and loan");
    }

    @Test
    void replace_onALoan_isAllowed() {
        Account loan = account(AccountType.LOAN);
        when(accessResolver.requireOwner(10L, 1L)).thenReturn(loan);
        when(memberRepository.findById(1L)).thenReturn(Optional.of(ALICE));
        when(memberRepository.findById(2L)).thenReturn(Optional.of(BOB));
        when(ownershipRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        assertThat(service.replace(10L, 1L, request(1L, "50", 2L, "50")).shares()).hasSize(2);
    }

    @Test
    void replace_emptyList_clearsTheSplit() {
        Account house = account(AccountType.REAL_ESTATE);
        when(accessResolver.requireOwner(10L, 1L)).thenReturn(house);

        OwnershipResponse result = service.replace(10L, 1L, new OwnershipRequest(List.of()));

        verify(ownershipRepository).deleteAllForAccount(10L);
        verify(ownershipRepository, never()).saveAll(any());
        // Back to the implicit default rather than an empty split that would zero everyone.
        assertThat(result.shares()).singleElement()
            .satisfies(s -> {
                assertThat(s.memberId()).isEqualTo(1L);
                assertThat(s.sharePercent()).isEqualByComparingTo("100");
                assertThat(s.isOwner()).isTrue();
            });
    }

    @Test
    void get_withNoRows_reportsTheImplicitFullShare() {
        Account house = account(AccountType.REAL_ESTATE);
        when(accessResolver.requireReadable(10L, 1L)).thenReturn(house);
        when(ownershipRepository.findByAccountId(10L)).thenReturn(List.of());

        OwnershipResponse result = service.get(10L, 1L);

        assertThat(result.totalAssigned()).isEqualByComparingTo("100");
        assertThat(result.unassigned()).isEqualByComparingTo("0");
    }

    @Test
    void get_marksTheOwnerAmongTheHolders() {
        Account house = account(AccountType.REAL_ESTATE);
        when(accessResolver.requireReadable(10L, 2L)).thenReturn(house);
        when(ownershipRepository.findByAccountId(10L)).thenReturn(List.of(
            AccountOwnership.builder().account(house).member(ALICE).sharePercent(new BigDecimal("50")).build(),
            AccountOwnership.builder().account(house).member(BOB).sharePercent(new BigDecimal("50")).build()));

        OwnershipResponse result = service.get(10L, 2L);

        assertThat(result.shares()).filteredOn(OwnershipResponse.MemberShare::isOwner)
            .singleElement()
            .satisfies(s -> assertThat(s.memberId()).isEqualTo(1L));
    }
}
