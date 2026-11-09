package com.picsou.dto;

import com.picsou.model.Account;

/**
 * The bank behind an account that an aggregator (SimpleFIN) delivered, so Chase and Fidelity
 * accounts stay distinguishable although both carry {@code provider = "SimpleFIN"}.
 *
 * <p>The domain itself is not exposed: the browser asks the logo endpoint by account id, and the
 * server looks the host up. {@code hasLogo} only says whether there is a host to look up.
 */
public record InstitutionResponse(String name, boolean hasLogo) {

    /** Null when the account has no institution of its own. */
    public static InstitutionResponse from(Account a) {
        String name = a.getInstitutionName();
        if (name == null || name.isBlank()) return null;
        String domain = a.getInstitutionDomain();
        return new InstitutionResponse(name, domain != null && !domain.isBlank());
    }
}
