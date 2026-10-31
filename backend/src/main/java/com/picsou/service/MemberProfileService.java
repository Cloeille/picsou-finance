package com.picsou.service;

import com.picsou.dto.MemberProfileRequest;
import com.picsou.dto.MemberProfileResponse;
import com.picsou.model.FamilyMember;
import com.picsou.model.MemberProfile;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.MemberProfileRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.Period;

/**
 * Reads and replaces a member's personal and fiscal profile.
 *
 * <p>Shaped after {@link AllocationTargetService}, including the part that matters most: reading
 * a profile that does not exist returns an empty instance and <em>does not</em> create a row.
 * That keeps "never stated" distinguishable from "stated as blank", which is the whole point of
 * an all-nullable table.
 */
@Service
@Transactional(readOnly = true)
public class MemberProfileService {

    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");

    private final MemberProfileRepository profileRepository;
    private final FamilyMemberRepository memberRepository;

    public MemberProfileService(MemberProfileRepository profileRepository,
                                FamilyMemberRepository memberRepository) {
        this.profileRepository = profileRepository;
        this.memberRepository = memberRepository;
    }

    /** The member's stored profile, or an unsaved empty one. */
    public MemberProfile profileFor(Long memberId) {
        return profileRepository.findByMemberId(memberId)
            .orElseGet(() -> MemberProfile.builder().build());
    }

    public MemberProfileResponse get(Long memberId) {
        return toResponse(profileFor(memberId));
    }

    @Transactional
    public MemberProfileResponse replace(Long memberId, MemberProfileRequest request) {
        MemberProfile profile = profileRepository.findByMemberId(memberId)
            .orElseGet(() -> {
                FamilyMember member = memberRepository.findById(memberId)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown member"));
                return MemberProfile.builder().member(member).build();
            });

        profile.setBirthDate(request.birthDate());
        profile.setMarginalTaxRate(request.marginalTaxRate());
        profile.setHouseholdStatus(request.householdStatus());
        profile.setTaxHouseholdParts(request.taxHouseholdParts());
        profile.setDependents(request.dependents());
        profile.setAnnualGrossIncome(request.annualGrossIncome());
        profile.setMonthlyNetBeforeTax(request.monthlyNetBeforeTax());
        profile.setWithholdingTaxRate(request.withholdingTaxRate());
        profile.setMonthlySavingsCapacity(request.monthlySavingsCapacity());
        profile.setTargetRetirementAge(request.targetRetirementAge());
        profile.setRiskProfile(request.riskProfile());

        return toResponse(profileRepository.save(profile));
    }

    private MemberProfileResponse toResponse(MemberProfile p) {
        return new MemberProfileResponse(
            p.getBirthDate(),
            ageOf(p.getBirthDate()),
            p.getMarginalTaxRate(),
            p.getHouseholdStatus(),
            p.getTaxHouseholdParts(),
            p.getDependents(),
            p.getAnnualGrossIncome(),
            p.getMonthlyNetBeforeTax(),
            p.getWithholdingTaxRate(),
            monthlyNetIncome(p.getMonthlyNetBeforeTax(), p.getWithholdingTaxRate()),
            p.getMonthlySavingsCapacity(),
            p.getTargetRetirementAge(),
            p.getRiskProfile()
        );
    }

    /** Years completed today — which is why the date is what gets stored, not this number. */
    private static Integer ageOf(LocalDate birthDate) {
        return birthDate == null ? null : Period.between(birthDate, LocalDate.now()).getYears();
    }

    /**
     * What actually reaches the account: the payslip's net before tax, less the withholding
     * applied to it.
     *
     * <p>Gross does not appear here, and cannot: social contributions come off first at a rate
     * that varies by status and that nobody knows offhand. Deriving net from gross would mean
     * storing that rate on the member's behalf.
     *
     * <p>Null unless both figures are stated. A blank withholding rate means "not said", not
     * "zero" — the Goals page would otherwise present a rate built on a number nobody gave it.
     */
    private static BigDecimal monthlyNetIncome(BigDecimal netBeforeTax, BigDecimal withholdingRate) {
        if (netBeforeTax == null || withholdingRate == null) return null;
        return netBeforeTax
            .multiply(ONE_HUNDRED.subtract(withholdingRate))
            .divide(ONE_HUNDRED, 2, RoundingMode.HALF_UP);
    }
}
