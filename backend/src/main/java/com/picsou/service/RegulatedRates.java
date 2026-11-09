package com.picsou.service;

import java.math.BigDecimal;

/**
 * Current official rates for French regulated savings books.
 *
 * <p>Rates are expressed as percentages (e.g. {@code 2.40} for 2.40 %) and are
 * always NET of tax (regulated livrets are tax-exempt in France).</p>
 *
 * <p>These constants are used as <em>default suggestions</em> only; the user can override
 * any rate in their {@code SavingsInterestConfig}.</p>
 *
 * <p>Sources: service-public.fr / Banque de France — updated 2025-02.</p>
 */
public final class RegulatedRates {

    private RegulatedRates() {}

    // source: service-public.fr, updated 2025-02
    // Livret A: 2.40 %, effective 2025-02-01 (reduced from 3.00 % set 2023-08-01)
    public static final BigDecimal LIVRET_A = new BigDecimal("2.40");

    // LDDS tracks Livret A identically.
    public static final BigDecimal LDDS = LIVRET_A;

    // LEP: 3.50 %, effective 2025-02-01 (reduced from 4.00 % set 2024-02-01)
    public static final BigDecimal LEP = new BigDecimal("3.50");

    // COMMERCIAL has no regulated rate — the user must supply their own.
}
