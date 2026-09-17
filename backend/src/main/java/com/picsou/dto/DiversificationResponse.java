package com.picsou.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * How the member's listed holdings spread across sectors and regions.
 *
 * <p>Covers the equity sleeve only — savings, property and crypto have no sector. ETFs are
 * looked through to their composition; a single share contributes its whole value to one sector
 * and one country.
 *
 * @param classifiedValueEur   value the breakdown could actually place
 * @param unclassifiedValueEur value it could not, reported rather than renormalised away — the
 *                             same discipline as the {@code Others} remainder in the holding
 *                             modal and {@code Valuation.anyPriced}
 * @param unclassified         the lines a breakdown could not fully place, carrying what the UI
 *                             needs to correct them by hand. Listing them beats a bare count:
 *                             the securities no provider covers (employee-savings FCPEs, unlisted
 *                             funds) are precisely the ones that will never resolve on their own
 */
public record DiversificationResponse(
    BigDecimal totalValueEur,
    BigDecimal classifiedValueEur,
    BigDecimal unclassifiedValueEur,
    BigDecimal coveragePercent,
    List<UnclassifiedLine> unclassified,
    Breakdown sectors,
    Breakdown countries,
    List<SecurityInfo> securities
) {

    /**
     * A holding the breakdown could not fully place, with everything needed to fix it by hand.
     *
     * @param accountId    an account the ticker is held in. The write is account-scoped because
     *                     ownership is what authorises an override; when the same ticker sits in
     *                     two accounts any one of them will do, since the row is keyed on
     *                     (member, ticker)
     * @param sectorMissing  no sector could be resolved and none was set by hand
     * @param countryMissing ditto for the country. A line can be missing one and not the other:
     *                     Yahoo knows Apple's sector, only the ISIN gives its domicile
     * @param profileLooked  whether a provider lookup has happened at all, so the UI can say
     *                     "not looked up yet" rather than implying the security is unknowable
     */
    public record UnclassifiedLine(
        String ticker,
        String name,
        Long accountId,
        BigDecimal valueEur,
        boolean sectorMissing,
        boolean countryMissing,
        boolean profileLooked
    ) {}

    /**
     * @param score        0-100, from the effective number of positions {@code 1/Σw²} against a
     *                     target count. Computed over the classified part only; read it next to
     *                     {@code coveragePercent}
     * @param effectiveCount the inverse Herfindahl index — how many buckets this really is,
     *                     which is what makes 40/30/30 read as 3 and 96/2/2 read as 1
     * @param slices       descending, percentages of the classified value
     * @param basis        {@code EXPOSURE} for a pure look-through, {@code MIXED} once a directly
     *                     held share contributes its domicile — the two are different quantities
     *                     and the UI has to say so
     */
    /**
     * @param classifiedValueEur what this axis could place, which is not the same as the other
     *                           axis's: a share often has a known sector and no domicile, and a
     *                           fund may disclose its countries far more completely than its
     *                           sectors. The top-level {@code coveragePercent} reports the more
     *                           generous of the two, so without these a weak axis hides behind a
     *                           strong one
     */
    public record Breakdown(
        int score,
        BigDecimal effectiveCount,
        int targetCount,
        String basis,
        BigDecimal classifiedValueEur,
        BigDecimal coveragePercent,
        List<Slice> slices
    ) {}

    /**
     * One bar of a breakdown, with the holdings behind it.
     *
     * <p>A separate record from {@link WeightedSlice} rather than a widening of it: that one is
     * shared with {@code EtfComposition} and the single-security insight modal, where a
     * contributor means nothing, and nine Java files construct it.
     *
     * @param valueEur         euros this slice represents, so a tooltip need not re-derive it
     * @param contributorCount how many holdings really feed it, which is not
     *                         {@code contributors.size()} once the tail is folded
     */
    public record Slice(
        String label,
        BigDecimal percent,
        BigDecimal valueEur,
        List<Contributor> contributors,
        int contributorCount
    ) {}

    /**
     * One holding's share of one slice — why "France" is 8.4 %.
     *
     * @param ticker null on the folded tail of small contributors; the name and account for a
     *               real one live in {@link DiversificationResponse#securities()} rather than
     *               being repeated across every slice it appears in
     */
    public record Contributor(String ticker, BigDecimal valueEur, BigDecimal sharePercent) {}

    /**
     * Every security appearing as a contributor, once.
     *
     * <p>A dictionary rather than inline fields: one ETF lands in a dozen slices across two axes,
     * and repeating its name and account each time is most of the payload for none of the
     * information.
     */
    public record SecurityInfo(String ticker, String name, Long accountId, BigDecimal valueEur) {}
}
