package com.picsou.service;

import com.picsou.port.BankConnectorPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Resolves a bank's logo from the connector's own institution catalog.
 *
 * <p>Server-side by design: a client-supplied logo URL is never trusted or persisted, since
 * nothing between an arbitrary URL and the Accounts page {@code <img src>} would validate its
 * scheme or host. A client names an institution; the server decides what image that means.
 *
 * <p>Extracted from {@link SyncService} once manual accounts needed the same lookup — a bank
 * a user picked by hand in the account form deserves the same logo as one they connected. One
 * copy of the matching tiers means the two paths cannot drift apart. See
 * {@code docs/features/bank-logos.md}.
 */
@Component
public class BankLogoResolver {

    private static final Logger log = LoggerFactory.getLogger(BankLogoResolver.class);

    private final BankConnectorPort bankConnector;

    public BankLogoResolver(BankConnectorPort bankConnector) {
        this.bankConnector = bankConnector;
    }

    /**
     * The institution's logo, or empty when the catalog has no match (or no logo for it).
     *
     * <p>Propagates the connector's failure so a caller that must tell "searched and found
     * nothing" from "could not search" — the once-per-requisition backfill marker — still can.
     * Callers with no such distinction to make want {@link #logoUrlOrNull} instead.
     *
     * @param country       narrows the catalog fetch; {@code null} searches every country, which
     *                      is a multi-megabyte response and belongs only on rare paths
     * @param institutionId the catalog's round-trip token, or {@code null} when the caller only
     *                      has a name to go on — matching then falls straight to the name tier
     */
    public Optional<String> logoUrl(String country, String institutionId, String institutionName) {
        List<BankConnectorPort.InstitutionData> matches =
            bankConnector.searchInstitutions(institutionName, country);
        return findInstitution(matches, institutionId, institutionName)
            .map(BankConnectorPort.InstitutionData::logoUrl);
    }

    /**
     * {@link #logoUrl} with the failure swallowed: a catalog outage, or an Enable Banking
     * install that was never configured, must not fail the write it decorates. The account or
     * requisition simply keeps showing its color.
     */
    public String logoUrlOrNull(String country, String institutionId, String institutionName) {
        try {
            return logoUrl(country, institutionId, institutionName).orElse(null);
        } catch (Exception ex) {
            log.warn("Could not resolve logo for institution {} ({}): {}",
                institutionId, institutionName, ex.getMessage());
            return null;
        }
    }

    /**
     * institutionId format: {@code "BankName::FR::personal"} (name::country::psuType) — see
     * {@link BankConnectorPort#parseInstitutionId}. Returns {@code null} for a blank or absent
     * country rather than {@link BankConnectorPort#DEFAULT_COUNTRY}, so a caller that wants an
     * unfiltered search gets one and a caller that needs a concrete country picks its own
     * fallback knowingly.
     */
    public static String countryOf(String institutionId) {
        if (institutionId == null) return null;
        String country = BankConnectorPort.parseInstitutionId(institutionId).country();
        return country.isBlank() ? null : country;
    }

    /**
     * Matches by exact institution id, then on name+country alone, and only then by
     * name. The middle tier exists for requisitions stored before PSU types were
     * modelled: they hold the two-segment {@code "BoursoBank::FR"} while the catalog
     * now returns {@code "BoursoBank::FR::personal"}, and dropping straight to the
     * name tier would lose the country preference — and pick arbitrarily for a bank
     * listed under both PSU types.
     *
     * <p>A {@code null} id matches neither of the first two tiers, so a caller holding
     * only a bank name lands on the name tier without a special case.
     */
    static Optional<BankConnectorPort.InstitutionData> findInstitution(
        List<BankConnectorPort.InstitutionData> candidates, String institutionId, String institutionName
    ) {
        return candidates.stream()
            .filter(i -> i.id().equals(institutionId))
            .findFirst()
            .or(() -> candidates.stream()
                .filter(i -> institutionId != null && institutionKey(i.id()).equals(institutionKey(institutionId)))
                .findFirst())
            .or(() -> candidates.stream()
                .filter(i -> i.name().equalsIgnoreCase(institutionName))
                .findFirst());
    }

    /**
     * Drops the PSU-type segment so old and new institution ids compare equal, and
     * normalizes what is left: a stored id was written from the catalog name of the
     * day, so a later casing change on the provider side would otherwise miss this
     * tier and fall through to the name-only one, losing the country preference.
     */
    private static String institutionKey(String institutionId) {
        if (institutionId == null) return "";
        String[] parts = institutionId.split("::");
        return parts.length > 1
            ? parts[0].trim().toLowerCase(Locale.ROOT) + "::" + parts[1].trim().toUpperCase(Locale.ROOT)
            : institutionId.trim().toLowerCase(Locale.ROOT);
    }
}
