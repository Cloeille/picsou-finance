package com.picsou.service;

import com.picsou.dto.MemberProfileRequest;
import com.picsou.dto.MemberProfileResponse;
import com.picsou.model.FamilyMember;
import com.picsou.model.HouseholdStatus;
import com.picsou.model.MemberProfile;
import com.picsou.model.RiskProfile;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.MemberProfileRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MemberProfileServiceTest {

    @Mock MemberProfileRepository profileRepository;
    @Mock FamilyMemberRepository memberRepository;

    @InjectMocks MemberProfileService service;

    private static MemberProfileRequest request(LocalDate birthDate, String tmi, String annualIncome) {
        return new MemberProfileRequest(
            birthDate,
            tmi == null ? null : new BigDecimal(tmi),
            HouseholdStatus.COUPLE,
            new BigDecimal("2.5"),
            (short) 1,
            annualIncome == null ? null : new BigDecimal(annualIncome),
            annualIncome == null ? null : new BigDecimal("2750"),
            annualIncome == null ? null : new BigDecimal("7.3"),
            new BigDecimal("800"),
            (short) 62,
            RiskProfile.DYNAMIC);
    }

    private static MemberProfile withPay(String netBeforeTax, String withholdingRate) {
        return MemberProfile.builder()
            .monthlyNetBeforeTax(netBeforeTax == null ? null : new BigDecimal(netBeforeTax))
            .withholdingTaxRate(withholdingRate == null ? null : new BigDecimal(withholdingRate))
            .build();
    }

    @Test
    void anUnstatedProfileReadsAsAllNull() {
        when(profileRepository.findByMemberId(42L)).thenReturn(Optional.empty());

        MemberProfileResponse response = service.get(42L);

        assertThat(response.birthDate()).isNull();
        assertThat(response.age()).isNull();
        assertThat(response.marginalTaxRate()).isNull();
        assertThat(response.monthlyNetIncome()).isNull();
    }

    @Test
    void readingAProfileNeverCreatesOne() {
        // The all-nullable table only works if "never stated" stays distinguishable from
        // "stated as blank". A read that persisted defaults would erase that distinction for
        // everyone who merely opened the settings page.
        when(profileRepository.findByMemberId(42L)).thenReturn(Optional.empty());

        service.get(42L);

        verify(profileRepository, never()).save(any());
    }

    @Test
    void replaceCreatesTheRowOnFirstUse() {
        FamilyMember member = FamilyMember.builder().id(42L).build();
        when(profileRepository.findByMemberId(42L)).thenReturn(Optional.empty());
        when(memberRepository.findById(42L)).thenReturn(Optional.of(member));
        when(profileRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        MemberProfileResponse response = service.replace(42L, request(LocalDate.of(1990, 6, 1), "30", "48000"));

        assertThat(response.marginalTaxRate()).isEqualByComparingTo("30");
        assertThat(response.householdStatus()).isEqualTo(HouseholdStatus.COUPLE);
        assertThat(response.riskProfile()).isEqualTo(RiskProfile.DYNAMIC);
    }

    @Test
    void replaceUpdatesTheExistingRowRatherThanAddingASecond() {
        MemberProfile existing = MemberProfile.builder()
            .id(7L).marginalTaxRate(new BigDecimal("11")).build();
        when(profileRepository.findByMemberId(42L)).thenReturn(Optional.of(existing));
        when(profileRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.replace(42L, request(null, "41", null));

        assertThat(existing.getMarginalTaxRate()).isEqualByComparingTo("41");
        verify(memberRepository, never()).findById(any());
    }

    @Test
    void aNullFieldClearsWhatWasThere() {
        // Null is a value here: it is how someone withdraws a figure they no longer stand behind.
        MemberProfile existing = MemberProfile.builder()
            .id(7L).annualGrossIncome(new BigDecimal("48000"))
            .monthlyNetBeforeTax(new BigDecimal("2750")).withholdingTaxRate(new BigDecimal("7.3"))
            .build();
        when(profileRepository.findByMemberId(42L)).thenReturn(Optional.of(existing));
        when(profileRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        MemberProfileResponse response = service.replace(42L, request(null, null, null));

        assertThat(existing.getAnnualGrossIncome()).isNull();
        assertThat(existing.getMonthlyNetBeforeTax()).isNull();
        assertThat(existing.getWithholdingTaxRate()).isNull();
        assertThat(response.monthlyNetIncome()).isNull();
    }

    @Test
    void theAgeIsDerivedFromTheDate_andTurnsOverOnTheBirthday() {
        // The reason the date is what gets stored: an age written down once is wrong the next
        // morning and nothing would ever correct it.
        LocalDate today = LocalDate.now();
        MemberProfile dayBefore = MemberProfile.builder()
            .birthDate(today.minusYears(40).plusDays(1)).build();
        MemberProfile onTheDay = MemberProfile.builder().birthDate(today.minusYears(40)).build();

        when(profileRepository.findByMemberId(1L)).thenReturn(Optional.of(dayBefore));
        when(profileRepository.findByMemberId(2L)).thenReturn(Optional.of(onTheDay));

        assertThat(service.get(1L).age()).isEqualTo(39);
        assertThat(service.get(2L).age()).isEqualTo(40);
    }

    @Test
    void theMonthlyNetIsTheNetBeforeTaxLessTheWithholding() {
        // Derived once here rather than in each screen that wants it -- the goals page's savings
        // rate is the first, and it must not disagree with anything added later.
        when(profileRepository.findByMemberId(42L)).thenReturn(Optional.of(withPay("2750", "7.3")));

        assertThat(service.get(42L).monthlyNetIncome()).isEqualByComparingTo("2549.25");
    }

    @Test
    void aMonthlyNetThatDoesNotDivideEvenlyIsRoundedToCents() {
        when(profileRepository.findByMemberId(42L)).thenReturn(Optional.of(withPay("2833", "11.7")));

        // 2833 x 88.3 / 100 = 2501.539 -> 2501.54
        assertThat(service.get(42L).monthlyNetIncome()).isEqualByComparingTo("2501.54");
    }

    @Test
    void aZeroWithholdingRateLeavesTheNetUntouched() {
        // Someone below the taxable threshold has a real 0%, and must get a real rate out.
        when(profileRepository.findByMemberId(42L)).thenReturn(Optional.of(withPay("1600", "0")));

        assertThat(service.get(42L).monthlyNetIncome()).isEqualByComparingTo("1600.00");
    }

    @Test
    void theMonthlyNetIsNullUntilBothFiguresAreStated() {
        // A blank withholding rate means "not said", not "zero". Treating it as zero would put a
        // savings rate on screen built on a number nobody supplied -- it would read as a
        // measurement while being an artefact.
        when(profileRepository.findByMemberId(1L)).thenReturn(Optional.of(withPay("2750", null)));
        when(profileRepository.findByMemberId(2L)).thenReturn(Optional.of(withPay(null, "7.3")));

        assertThat(service.get(1L).monthlyNetIncome()).isNull();
        assertThat(service.get(2L).monthlyNetIncome()).isNull();
    }

    @Test
    void theGrossIncomeIsCarriedButNeverFeedsTheNet() {
        // Gross cannot reach net on its own: social contributions come off first, at a rate that
        // varies by status. It is context for the export, not an input to any calculation here.
        when(profileRepository.findByMemberId(42L)).thenReturn(Optional.of(
            MemberProfile.builder().annualGrossIncome(new BigDecimal("48000")).build()));

        MemberProfileResponse response = service.get(42L);

        assertThat(response.annualGrossIncome()).isEqualByComparingTo("48000");
        assertThat(response.monthlyNetIncome()).isNull();
    }

    @Test
    void replaceRefusesAnUnknownMember() {
        when(profileRepository.findByMemberId(99L)).thenReturn(Optional.empty());
        when(memberRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.replace(99L, request(null, "30", null)))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
