package com.picsou.model;

/**
 * The five layers of the investment pyramid, from the base upwards.
 *
 * <p>The pyramid asks a different question from the Accounts page's asset filters, so the two
 * mappings are deliberately separate: {@code ASSET_FILTER_MAP} groups accounts the way a user
 * browses them, this one groups them by the role they play in a portfolio. Merging them would
 * force one of the two answers to be wrong.
 */
public enum WealthTier {

    /** Liquid, capital-guaranteed cash covering a few months of living costs. */
    SAFETY_NET,

    /** Property, held directly or through SCPI shares. */
    REAL_ESTATE,

    /** Listed equity, whatever the wrapper: PEA, brokerage, life insurance, employee savings. */
    EQUITY,

    CRYPTO,

    /** Gold, watches, art, startups — everything that is neither listed nor property. */
    ALTERNATIVE;

    /**
     * The tier an account falls into by default.
     *
     * <p>An exhaustive switch with no {@code default} on purpose: a new {@link AccountType} must
     * stop the compilation here rather than fall into a catch-all. The frontend's
     * {@code TYPE_TO_GROUP} documents the opposite failure — a type added to the enum but not to
     * the map made those accounts silently vanish from the chart. A {@code Map} cannot have this
     * property; a switch expression can.
     *
     * <p>{@code LOAN} answers {@code REAL_ESTATE} because a mortgage is a claim against the
     * property it finances, but callers must not read that as "loans are assets": the pyramid is
     * built from assets only, and property enters it net of its debt via
     * {@code RealEstateSummaryService}. The value exists so the switch stays total.
     */
    public static WealthTier of(AccountType type) {
        return switch (type) {
            case CHECKING, SAVINGS, LEP, LIVRET_A, LDDS, LIVRET_JEUNE, PEL, CEL -> SAFETY_NET;
            case REAL_ESTATE, SCPI, LOAN                                        -> REAL_ESTATE;
            case PEA, COMPTE_TITRES, EMPLOYEE_SAVINGS, ASSURANCE_VIE            -> EQUITY;
            case CRYPTO                                                         -> CRYPTO;
            case OTHER                                                          -> ALTERNATIVE;
        };
    }
}
