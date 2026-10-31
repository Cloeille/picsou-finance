package com.picsou.service.budget;

import com.picsou.dto.BudgetSettingsRequest;
import com.picsou.dto.BudgetSettingsResponse;
import com.picsou.model.BudgetSettings;
import com.picsou.repository.BudgetSettingsRepository;
import com.picsou.repository.FamilyMemberRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Reads/writes the per-member {@code cycleStartDay} (the payday the budget cycle resets on)
 * and resolves the current cycle bounds via {@link BudgetCycle}. Settings are created
 * on-demand with the default day (1) so callers never see an empty state.
 */
@Service
@Transactional(readOnly = true)
public class BudgetSettingsService {

    private final BudgetSettingsRepository settingsRepository;
    private final FamilyMemberRepository familyMemberRepository;

    public BudgetSettingsService(
        BudgetSettingsRepository settingsRepository,
        FamilyMemberRepository familyMemberRepository
    ) {
        this.settingsRepository = settingsRepository;
        this.familyMemberRepository = familyMemberRepository;
    }

    /** The member's cycle start day, defaulting to 1 if they have no settings row yet. */
    public int cycleStartDay(Long memberId) {
        return settingsRepository.findByMemberId(memberId)
            .map(BudgetSettings::getCycleStartDay)
            .orElse(1);
    }

    /**
     * Whether this member has opted into online logo fetching. Defaults to {@code false}
     * when no settings row exists yet — a pure read (no lazy insert), so it stays safe to
     * call from the read-only logo proxy without forcing a writable transaction.
     */
    public boolean logoFetchEnabled(Long memberId) {
        return settingsRepository.findByMemberId(memberId)
            .map(BudgetSettings::isLogoFetchEnabled)
            .orElse(false);
    }

    /**
     * Read-write on purpose: {@link #getOrCreate} lazily inserts the default settings row on
     * first read, and that internal call bypasses Spring's proxy — so the write inherits THIS
     * transaction and it must be writable, else Postgres rejects the INSERT ("cannot execute
     * INSERT in a read-only transaction").
     */
    @Transactional
    public BudgetSettingsResponse get(Long memberId) {
        BudgetSettings settings = getOrCreate(memberId);
        return toResponse(settings, LocalDate.now());
    }

    @Transactional
    public BudgetSettingsResponse update(BudgetSettingsRequest req, Long memberId) {
        BudgetSettings settings = getOrCreate(memberId);
        settings.setCycleStartDay(req.cycleStartDay());
        settings.setLogoFetchEnabled(req.logoFetchEnabled());
        settings.setAiCategorizationEnabled(req.aiCategorizationEnabled());
        settings.setAiMode(req.aiMode());
        settings.setAiConfidenceThreshold(req.aiConfidenceThreshold());
        settings.setUpdatedAt(Instant.now());
        return toResponse(settingsRepository.save(settings), LocalDate.now());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public BudgetSettings getOrCreate(Long memberId) {
        return settingsRepository.findByMemberId(memberId)
            .orElseGet(() -> settingsRepository.save(BudgetSettings.builder()
                .member(familyMemberRepository.getReferenceById(memberId))
                .cycleStartDay(1)
                .build()));
    }

    private BudgetSettingsResponse toResponse(BudgetSettings settings, LocalDate on) {
        BudgetCycle.CycleRange cycle = BudgetCycle.cycleFor(on, settings.getCycleStartDay());
        return BudgetSettingsResponse.of(settings, cycle.start(), cycle.end());
    }
}
