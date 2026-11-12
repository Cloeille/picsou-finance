package com.picsou.port;

/**
 * The regional banks offered by the Banque Populaire login page. The constant name is what the
 * sidecar's {@code /initiate} expects and what {@code sidecar_session.region} stores.
 */
public enum BanquePopulaireRegion {
    ALSACE_LORRAINE_CHAMPAGNE("Banque Populaire Alsace Lorraine Champagne"),
    AQUITAINE_CENTRE_ATLANTIQUE("Banque Populaire Aquitaine Centre Atlantique"),
    AUVERGNE_RHONE_ALPES("Banque Populaire Auvergne Rhône Alpes"),
    BOURGOGNE_FRANCHE_COMTE("Banque Populaire Bourgogne Franche-Comté"),
    BRED("BRED Banque Populaire"),
    GRAND_OUEST("Banque Populaire Grand Ouest"),
    MEDITERRANEE("Banque Populaire Méditerranée"),
    NORD("Banque Populaire du Nord"),
    OCCITANE("Banque Populaire Occitane"),
    RIVES_DE_PARIS("Banque Populaire Rives de Paris"),
    SUD("Banque Populaire du Sud"),
    VAL_DE_FRANCE("Banque Populaire Val de France");

    private final String institutionName;

    BanquePopulaireRegion(String institutionName) {
        this.institutionName = institutionName;
    }

    /**
     * The regional bank's official name: stamped on {@code account.provider} by the sync, and the
     * name its logo is looked up under in the Enable Banking catalog, so an account looks the
     * same whether this connector or Enable Banking imported it.
     */
    public String institutionName() {
        return institutionName;
    }
}
