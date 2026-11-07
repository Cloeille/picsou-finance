package com.picsou.port;

/**
 * The domain's typed contract onto the Caisse d'Epargne sidecar. Everything that knows
 * about the bank's OAuth/SAML replay and its JSON APIs lives in the sidecar behind
 * {@code CaisseEpargneAdapter}. The calls and records shared with the other BPCE banks live on
 * {@link BpcePort}; only the first login call is the bank's own.
 */
public interface CaisseEpargnePort extends BpcePort {

    /**
     * {@code POST /initiate}: types the identifier on the bank's login page and answers with the
     * ten-key pad for the user to click. No password, no click on the pad. One attempt, no retry.
     */
    InitiateResult initiateAuth(String customerId);
}
