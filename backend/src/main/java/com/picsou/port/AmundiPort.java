package com.picsou.port;

import java.math.BigDecimal;
import java.util.List;

/**
 * Read-only access to an Amundi Épargne Salariale account.
 *
 * <p>Amundi gates its login behind a captcha and a mandatory second factor, so
 * authentication is always interactive: {@link #initiateAuth} starts it and
 * {@link #completeAuth} finishes it once the user has validated on their phone.
 * The returned session state is opaque to the domain -- it is whatever the
 * sidecar needs to replay the account, and it is only ever stored encrypted.
 */
public interface AmundiPort {
    InitiateResult initiateAuth(String login, String password);

    /** {@code code} is null for an app push: there is nothing for the user to type. */
    String completeAuth(String processId, String code);

    List<PlanData> fetchPlans(String sessionState);

    record InitiateResult(String processId, boolean mfaRequired, String mfaType, String sessionState) {}

    /**
     * One FCPE line inside a plan. Everything is EUR-denominated: épargne
     * salariale funds are French-domiciled and quoted in euros, and Amundi
     * reports the valuation itself rather than leaving it to be recomputed.
     */
    record Position(String isin, String fundCode, String label, BigDecimal quantity,
                    BigDecimal unitValue, BigDecimal valueEur, BigDecimal pnlEur) {}

    /** One dispositif -- a PEG, PERCO, PER... -- and the lines held inside it. */
    record PlanData(String externalId, String name, String planKind, String employer,
                    BigDecimal balanceEur, List<Position> positions, boolean snapshotComplete) {}
}
