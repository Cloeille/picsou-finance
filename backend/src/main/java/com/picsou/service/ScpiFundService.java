package com.picsou.service;

import com.picsou.dto.ScpiFundResponse;
import com.picsou.exception.ResourceNotFoundException;
import com.picsou.model.Account;
import com.picsou.model.ScpiPosition;
import com.picsou.model.ScpiProvider;
import com.picsou.model.ScpiProviderFund;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.ScpiPositionRepository;
import com.picsou.repository.ScpiProviderFundRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * The funds a CORUM or Sofidy sync has seen, and the member's choice to ignore one.
 *
 * <p>Ignoring a fund unlinks its account and sets the tombstone the sync reads, so
 * the account is neither updated nor recreated. The account itself is kept: it may
 * hold history the member wants. Restoring a fund only lifts the tombstone; the next
 * sync links or creates the account again.
 */
@Service
public class ScpiFundService {

    private final ScpiProviderFundRepository fundRepository;
    private final ScpiPositionRepository positionRepository;
    private final FamilyMemberRepository memberRepository;

    public ScpiFundService(
        ScpiProviderFundRepository fundRepository,
        ScpiPositionRepository positionRepository,
        FamilyMemberRepository memberRepository
    ) {
        this.fundRepository = fundRepository;
        this.positionRepository = positionRepository;
        this.memberRepository = memberRepository;
    }

    @Transactional(readOnly = true)
    public List<ScpiFundResponse> list(Long memberId) {
        return fundRepository.findByMemberIdOrderByProviderAscLabelAsc(memberId).stream()
            .map(row -> {
                Optional<Account> account = linked(memberId, row.getProvider(), row.getFundCode())
                    .map(ScpiPosition::getAccount);
                return new ScpiFundResponse(
                    row.getProvider(),
                    row.getFundCode(),
                    row.getLabel(),
                    row.isIgnored(),
                    row.getLastOutcome(),
                    row.getLastSeenAt(),
                    row.getLastSyncedAt(),
                    account.map(Account::getId).orElse(null),
                    account.map(Account::getName).orElse(null)
                );
            })
            .toList();
    }

    /** Unlinks the fund from its account, if any, and stops every later sync from touching it. */
    @Transactional
    public void ignore(Long memberId, ScpiProvider provider, String fundCode) {
        String code = requireCode(fundCode);
        linked(memberId, provider, code).ifPresent(position -> {
            ScpiFundReconciler.setFundCode(position, provider, null);
            positionRepository.save(position);
        });
        tombstone(memberId, provider, code);
    }

    /** Lifts the tombstone. Nothing is written until the next sync. */
    @Transactional
    public void restore(Long memberId, ScpiProvider provider, String fundCode) {
        ScpiProviderFund row = fundRepository
            .findByMemberIdAndProviderAndFundCode(memberId, provider, requireCode(fundCode))
            .orElseThrow(() -> new ResourceNotFoundException("SCPI fund not found"));
        row.setIgnored(false);
        fundRepository.save(row);
    }

    /**
     * Records that the member detached this fund on purpose -- by deleting its
     * account, or by clearing the code on the form -- so a sync does not bring the
     * account back. Called inside the caller's transaction.
     */
    @Transactional
    public void tombstone(Long memberId, ScpiProvider provider, String fundCode) {
        if (fundCode == null || fundCode.isBlank()) {
            return;
        }
        ScpiProviderFund row = fundRepository
            .findByMemberIdAndProviderAndFundCode(memberId, provider, fundCode)
            .orElseGet(() -> ScpiProviderFund.builder()
                .member(memberRepository.getReferenceById(memberId))
                .provider(provider)
                .fundCode(fundCode)
                .build());
        row.setIgnored(true);
        fundRepository.save(row);
    }

    /** A code typed or picked on the form is an explicit link: it overrides an earlier ignore. */
    @Transactional
    public void clearTombstone(Long memberId, ScpiProvider provider, String fundCode) {
        if (fundCode == null || fundCode.isBlank()) {
            return;
        }
        fundRepository.findByMemberIdAndProviderAndFundCode(memberId, provider, fundCode)
            .filter(ScpiProviderFund::isIgnored)
            .ifPresent(row -> {
                row.setIgnored(false);
                fundRepository.save(row);
            });
    }

    private Optional<ScpiPosition> linked(Long memberId, ScpiProvider provider, String fundCode) {
        return switch (provider) {
            case CORUM -> positionRepository.findByMemberIdAndCorumFundCode(memberId, fundCode);
            case SOFIDY -> positionRepository.findByMemberIdAndSofidyFundCode(memberId, fundCode);
        };
    }

    private static String requireCode(String fundCode) {
        if (fundCode == null || fundCode.isBlank() || fundCode.length() > 40) {
            throw new IllegalArgumentException("Invalid fund code");
        }
        return fundCode.trim();
    }
}
