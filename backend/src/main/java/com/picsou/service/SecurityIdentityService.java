package com.picsou.service;

import com.picsou.adapter.OpenFigiIsinConverter;
import com.picsou.model.SecurityProfile;
import com.picsou.repository.SecurityProfileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Remembers which ISIN a ticker came from.
 *
 * <p>Every connector reports an ISIN and every sync converts it to a Yahoo ticker and drops it.
 * That loses the identifier the data sources actually resolve: Boursorama's search maps an ISIN
 * to the same symbol as the ticker, and a fund-facts lookup has no other key. Worse,
 * {@code OpenFigiIsinConverter.pickBest} prefers US OTC listings for non-US ISINs, so the ticker
 * that replaces it is frequently the one Boursorama cannot find — the conversion and the
 * downstream lookup actively work against each other.
 *
 * <p>Stored on {@code security_profile} rather than on the holding: a sync deletes an account's
 * holdings and re-inserts them, so a column there would survive only until the next run. Same
 * reasoning that kept {@code holding_classification} off {@code account_holding}.
 */
@Service
public class SecurityIdentityService {

    private static final Logger log = LoggerFactory.getLogger(SecurityIdentityService.class);

    private final SecurityProfileRepository repository;

    public SecurityIdentityService(SecurityProfileRepository repository) {
        this.repository = repository;
    }

    /**
     * Records ticker → ISIN pairs learned during a sync.
     *
     * <p>Never throws at the caller: this runs on the sync write path, and reference data that
     * the next sync will supply again is not worth failing an import over.
     */
    @Transactional
    public void record(Map<String, String> isinByTicker) {
        if (isinByTicker == null || isinByTicker.isEmpty()) return;

        for (Map.Entry<String, String> entry : isinByTicker.entrySet()) {
            try {
                remember(entry.getKey(), entry.getValue());
            } catch (Exception ex) {
                log.debug("Could not record ISIN {} for {}: {}",
                    entry.getValue(), entry.getKey(), ex.getMessage());
            }
        }
    }

    private void remember(String ticker, String isin) {
        if (ticker == null || ticker.isBlank() || !OpenFigiIsinConverter.isIsin(isin)) return;
        String upper = ticker.toUpperCase(Locale.ROOT);

        SecurityProfile profile = repository.findByTicker(upper).orElse(null);
        if (profile == null) {
            // Seeded with nothing but its identity. refreshedAt stays null, which refreshStale
            // already reads as "due" — so the next pass resolves it rather than treating an empty
            // row as freshly fetched.
            repository.save(SecurityProfile.builder()
                .ticker(upper)
                .assetType("UNKNOWN")
                .isin(isin)
                .build());
            return;
        }
        if (profile.getIsin() == null) {
            profile.setIsin(isin);
            repository.save(profile);
        }
    }

    /**
     * The best ISIN known for a ticker.
     *
     * <p>A ticker that <em>is</em> an ISIN wins outright — that is not a fallback but the common
     * case for employee-savings FCPEs, whose "ticker" is the ISIN because no exchange quotes them.
     */
    @Transactional(readOnly = true)
    public Optional<String> isinOf(String ticker) {
        if (ticker == null || ticker.isBlank()) return Optional.empty();
        String upper = ticker.toUpperCase(Locale.ROOT);

        if (OpenFigiIsinConverter.isIsin(upper)) return Optional.of(upper);

        return repository.findByTicker(upper)
            .map(SecurityProfile::getIsin)
            .filter(OpenFigiIsinConverter::isIsin);
    }
}
