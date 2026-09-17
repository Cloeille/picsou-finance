package com.picsou.service;

import com.picsou.dto.AllocationTargetsRequest;
import com.picsou.dto.AllocationTargetsResponse;
import com.picsou.model.FamilyMember;
import com.picsou.model.MemberAllocationProfile;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.MemberAllocationProfileRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AllocationTargetServiceTest {

    private static final Long MEMBER = 1L;

    @Mock MemberAllocationProfileRepository profileRepository;
    @Mock FamilyMemberRepository memberRepository;

    @InjectMocks AllocationTargetService service;

    @Test
    void anUnconfiguredMemberGetsTheDefaultsWithoutARowBeingCreated() {
        when(profileRepository.findByMemberId(MEMBER)).thenReturn(Optional.empty());

        AllocationTargetsResponse response = service.get(MEMBER);

        assertThat(response.realEstatePct()).isEqualByComparingTo("30.00");
        assertThat(response.equityPct()).isEqualByComparingTo("50.00");
        assertThat(response.cryptoPct()).isEqualByComparingTo("10.00");
        assertThat(response.alternativePct()).isEqualByComparingTo("10.00");
        assertThat(response.safetyNetMonths()).isEqualTo((short) 6);
        // Never set, and reading must not invent it: that is what keeps the safety net unrated.
        assertThat(response.monthlyEssentialExpenses()).isNull();
        // "Never configured" must stay distinguishable from "configured to today's defaults",
        // or a future change to the defaults could never reach anyone.
        verify(profileRepository, never()).save(any());
    }

    @Test
    void replacingCreatesTheRowForAMemberWhoNeverHadOne() {
        FamilyMember member = FamilyMember.builder().id(MEMBER).build();
        when(profileRepository.findByMemberId(MEMBER)).thenReturn(Optional.empty());
        when(memberRepository.findById(MEMBER)).thenReturn(Optional.of(member));
        when(profileRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.replace(MEMBER, new AllocationTargetsRequest(
            new BigDecimal("1850"), (short) 3, new BigDecimal("20"), new BigDecimal("60"),
            new BigDecimal("15"), new BigDecimal("5")));

        ArgumentCaptor<MemberAllocationProfile> saved =
            ArgumentCaptor.forClass(MemberAllocationProfile.class);
        verify(profileRepository).save(saved.capture());
        assertThat(saved.getValue().getMember()).isSameAs(member);
        assertThat(saved.getValue().getEquityPct()).isEqualByComparingTo("60");
        assertThat(saved.getValue().getSafetyNetMonths()).isEqualTo((short) 3);
        assertThat(saved.getValue().getMonthlyEssentialExpenses()).isEqualByComparingTo("1850");
    }

    @Test
    void replacingUpdatesTheExistingRowRatherThanAddingASecond() {
        MemberAllocationProfile existing = MemberAllocationProfile.builder()
            .id(7L).monthlyEssentialExpenses(new BigDecimal("1000")).build();
        when(profileRepository.findByMemberId(MEMBER)).thenReturn(Optional.of(existing));
        when(profileRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.replace(MEMBER, new AllocationTargetsRequest(
            new BigDecimal("2000"), (short) 6, new BigDecimal("30"), new BigDecimal("50"),
            new BigDecimal("10"), new BigDecimal("10")));

        verify(memberRepository, never()).findById(any());
        assertThat(existing.getMonthlyEssentialExpenses()).isEqualByComparingTo("2000");
    }

    @Test
    void clearingTheExpensesPutsTheSafetyNetBackToUnrated() {
        MemberAllocationProfile existing = MemberAllocationProfile.builder()
            .id(7L).monthlyEssentialExpenses(new BigDecimal("1000")).build();
        when(profileRepository.findByMemberId(MEMBER)).thenReturn(Optional.of(existing));
        when(profileRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AllocationTargetsResponse response = service.replace(MEMBER, new AllocationTargetsRequest(
            null, (short) 6, new BigDecimal("30"), new BigDecimal("50"),
            new BigDecimal("10"), new BigDecimal("10")));

        assertThat(response.monthlyEssentialExpenses()).isNull();
    }
}
