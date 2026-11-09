package com.picsou.port;

/**
 * The domain's typed contract onto the Banque Populaire sidecar, behind
 * {@code BanquePopulaireAdapter}. Banque Populaire runs on the BPCE platform, so everything but
 * the first login call is {@link BpcePort}'s.
 */
public interface BanquePopulairePort extends BpcePort {

    /**
     * {@code POST /initiate}: picks the customer's regional bank, types the identifier on the
     * bank's login page and answers with the ten-key pad for the user to click. No password, no
     * click on the pad. One attempt, no retry.
     */
    InitiateResult initiateAuth(String customerId, BanquePopulaireRegion region);
}
