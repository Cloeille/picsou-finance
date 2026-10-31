package com.picsou.model;

/** The client spaces that can sync a SCPI share. Stored by name in {@code scpi_provider_fund}. */
public enum ScpiProvider {
    CORUM("CORUM"),
    SOFIDY("Sofidy");

    private final String displayName;

    ScpiProvider(String displayName) {
        this.displayName = displayName;
    }

    /** What a synced account shows as its provider and management company. */
    public String displayName() {
        return displayName;
    }
}
