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
 * <p>Sources: service-public.fr / Banque de France — updated 2026-08-01.</p>
 */
public final class RegulatedRates {

    private RegulatedRates() {}

    // source: service-public.fr / economie.gouv.fr, updated 2026-08-01 (period 2026-08-01 to 2027-01-31)
    // Livret A: 1.70 %, raised from 1.50 % on 2026-08-01.
    public static final BigDecimal LIVRET_A = new BigDecimal("1.70");

    // LDDS tracks Livret A identically.
    public static final BigDecimal LDDS = LIVRET_A;

    // LEP: 2.50 %, maintained on 2026-08-01 (the formula would have cut it to 2.20 %).
    public static final BigDecimal LEP = new BigDecimal("2.50");

    // Legal deposit ceilings, informational only. Source: service-public.fr, 2026.
    public static final BigDecimal LIVRET_A_CEILING = new BigDecimal("22950.00");
    public static final BigDecimal LDDS_CEILING = new BigDecimal("12000.00");
    public static final BigDecimal LEP_CEILING = new BigDecimal("10000.00");

    // COMMERCIAL has no regulated rate — the user must supply their own.
}
