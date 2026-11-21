package com.picsou.model;

/** The client spaces that can sync a SCPI share. Stored by name in {@code scpi_provider_fund}. */
public enum ScpiProvider {
    CORUM("CORUM", false),
    SOFIDY("Sofidy", false),
    LOUVE("Louve Invest", true);

    private final String displayName;
    private final boolean distributor;

    ScpiProvider(String displayName, boolean distributor) {
        this.displayName = displayName;
        this.distributor = distributor;
    }

    /**
     * A distributor sells funds of several management companies. Its client names an account
     * after the distributor and the fund ("Louve Invest - Aurore"), where a management company's
     * fund name already carries its own brand ("CORUM XL"). {@link com.picsou.service.ScpiFundReconciler}
     * therefore also adopts an account named {@code <displayName> <fund label>} for a distributor,
     * and only for one.
     */
    public boolean distributor() {
        return distributor;
    }

    /** What a synced account shows as its provider and management company. */
    public String displayName() {
        return displayName;
    }
}
