package com.picsou.port;

import java.math.BigDecimal;
import java.util.List;

/**
 * Read-only access to a Crédit Mutuel Épargne Salariale saver's space, the one
 * CIC savers use too.
 *
 * <p>The login is a plain form; the site then asks for a mobile confirmation or
 * sends a code by SMS, unless it still remembers the device. {@link #initiateAuth}
 * takes the previous session so the sidecar can replay that remembered-device
 * cookie: a re-login with stored credentials then completes without anyone at
 * the phone.
 * The session state is opaque to the domain and only ever stored encrypted.
 */
public interface CmesPort {
    /** {@code previousSessionState} is null on a first login. {@code mfaType} is {@code APP_PUSH} or {@code SMS}. */
    InitiateResult initiateAuth(String login, String password, String previousSessionState);

    /**
     * The same login for a scheduled re-login with stored credentials: nobody is
     * there to approve, so the sidecar withdraws a mobile confirmation at once and
     * reports the session as expired rather than leaving a request on the phone.
     */
    InitiateResult reauthenticate(String login, String password, String previousSessionState);

    /**
     * Finishes the second factor: {@code code} is the SMS code, or null for a
     * mobile confirmation, which waits for the saver to approve in the app.
     */
    String completeAuth(String processId, String code);

    List<PlanData> fetchPlans(String sessionState);

    record InitiateResult(String processId, boolean mfaRequired, String mfaType, String sessionState) {}

    /**
     * One FCPE line inside a plan. Everything is EUR-denominated. Employer funds
     * usually show only the manager's fund code, no ISIN.
     */
    record Position(String isin, String fundCode, String label, BigDecimal quantity,
                    BigDecimal unitValue, BigDecimal valueEur, BigDecimal pnlEur) {}

    /**
     * One plan -- PEG, PEE, PERCOL... -- and the funds held inside it.
     * {@code legacyExternalId} is the id earlier builds gave the plan, or null;
     * an account still carrying it moves to {@code externalId}.
     */
    record PlanData(String externalId, String legacyExternalId, String name, String planKind, String employer,
                    BigDecimal balanceEur, List<Position> positions, boolean snapshotComplete) {}
}
