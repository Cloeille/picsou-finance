package com.picsou.model;

/**
 * Coarse allocation buckets derived from {@link AccountType}, used by the allocation view to
 * show how liquid/investable money is split. Patrimony that isn't part of cash allocation
 * (real estate, loans, misc) maps to {@link #OTHER} and is excluded from the allocation donut.
 */
public enum AssetClass {
    /** Everyday cash — current/checking accounts. */
    CURRENT,
    /** Regulated savings — Livret/LEP and generic savings. */
    SAVINGS,
    /** Market exposure — equities, securities accounts, crypto. */
    INVESTMENT,
    /** Not part of cash allocation (real estate, loans, other). */
    OTHER;

    public static AssetClass of(AccountType type) {
        return switch (type) {
            case CHECKING -> CURRENT;
            case LEP, SAVINGS, LIVRET_A, LDDS, LIVRET_JEUNE, PEL, CEL -> SAVINGS;
            case PEA, COMPTE_TITRES, CRYPTO, EMPLOYEE_SAVINGS, ASSURANCE_VIE -> INVESTMENT;
            // SCPI sits with real estate rather than with investments: it is property held on
            // paper, priced per share by no market this app can reach, so it is not part of the
            // cash allocation the donut describes. Same placement as in WealthTier.
            case REAL_ESTATE, SCPI, LOAN, OTHER -> OTHER;
        };
    }

    /** Whether contributions (incoming transfers) into this class are tracked as flux. */
    public boolean tracksContributions() {
        return this == SAVINGS || this == INVESTMENT;
    }
}
