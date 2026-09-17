package com.picsou.service;

import com.picsou.dto.AllocationTargetsRequest;
import com.picsou.dto.AllocationTargetsResponse;
import com.picsou.model.FamilyMember;
import com.picsou.model.MemberAllocationProfile;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.MemberAllocationProfileRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads and replaces a member's allocation profile.
 *
 * <p>Owns the shipped defaults, and deliberately does <em>not</em> write them: a member who has
 * never opened the targets form has no row, and reading their profile must not create one. That
 * keeps "never configured" distinguishable from "configured to the defaults", which is what lets
 * a future change to the defaults reach everyone who never expressed a preference.
 */
@Service
@Transactional(readOnly = true)
public class AllocationTargetService {

    private final MemberAllocationProfileRepository profileRepository;
    private final FamilyMemberRepository memberRepository;

    public AllocationTargetService(MemberAllocationProfileRepository profileRepository,
                                   FamilyMemberRepository memberRepository) {
        this.profileRepository = profileRepository;
        this.memberRepository = memberRepository;
    }

    /** The member's stored profile, or an unsaved instance carrying the defaults. */
    public MemberAllocationProfile profileFor(Long memberId) {
        return profileRepository.findByMemberId(memberId)
            .orElseGet(() -> MemberAllocationProfile.builder().build());
    }

    public AllocationTargetsResponse get(Long memberId) {
        return toResponse(profileFor(memberId));
    }

    @Transactional
    public AllocationTargetsResponse replace(Long memberId, AllocationTargetsRequest request) {
        MemberAllocationProfile profile = profileRepository.findByMemberId(memberId)
            .orElseGet(() -> {
                FamilyMember member = memberRepository.findById(memberId)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown member"));
                return MemberAllocationProfile.builder().member(member).build();
            });

        profile.setMonthlyEssentialExpenses(request.monthlyEssentialExpenses());
        profile.setSafetyNetMonths(request.safetyNetMonths());
        profile.setRealEstatePct(request.realEstatePct());
        profile.setEquityPct(request.equityPct());
        profile.setCryptoPct(request.cryptoPct());
        profile.setAlternativePct(request.alternativePct());

        return toResponse(profileRepository.save(profile));
    }

    private AllocationTargetsResponse toResponse(MemberAllocationProfile p) {
        return new AllocationTargetsResponse(
            p.getMonthlyEssentialExpenses(),
            p.getSafetyNetMonths(),
            p.getRealEstatePct(),
            p.getEquityPct(),
            p.getCryptoPct(),
            p.getAlternativePct()
        );
    }
}
