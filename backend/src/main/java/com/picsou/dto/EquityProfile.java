package com.picsou.dto;

/**
 * What a single listed company is, as far as one provider can tell.
 *
 * <p>Deliberately not an {@link EtfComposition}: a fund has a <em>distribution</em> of many
 * slices, a share has one sector and one country. Expressing the latter as
 * {@code [{technology, 100}]} would be a lie in the type, and would make the ETF pipeline treat
 * a stock as if it had a composition.
 *
 * <p>Either field may be null — providers answer what they know, and the resolver merges them
 * field by field rather than taking the first whole answer.
 *
 * @param sectorKey        stable key from the Morningstar taxonomy ({@code technology},
 *                         {@code basic_materials}…), the same vocabulary ETF sector slices use
 * @param countryKey       ISO 3166-1 alpha-2
 * @param countryIsDomicile true when the country is where the issuer is registered rather than
 *                         where its revenue comes from. Always true today; carried so the UI can
 *                         footnote a breakdown that mixes it with an ETF's look-through exposure
 */
public record EquityProfile(
    String sectorKey,
    String countryKey,
    String source,
    boolean countryIsDomicile
) {
    public boolean isEmpty() {
        return sectorKey == null && countryKey == null;
    }
}
