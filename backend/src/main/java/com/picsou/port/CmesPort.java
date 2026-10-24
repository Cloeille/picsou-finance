package com.picsou.port;

import java.math.BigDecimal;
import java.util.List;

/**
 * Read-only access to a Crédit Mutuel Épargne Salariale saver's space, the one
 * CIC savers use too.
 *
 * <p>The login is a plain form; the site then asks for a mobile confirmation
 * unless it still remembers the device. {@link #initiateAuth} takes the
 * previous session so the sidecar can replay that remembered-device cookie: a
 * re-login with stored credentials then completes without anyone at the phone.
 * The session state is opaque to the domain and only ever stored encrypted.
 */
public interface CmesPort {
    /** {@code previousSessionState} is null on a first login. */
    InitiateResult initiateAuth(String login, String password, String previousSessionState);

    /** Waits for the saver to approve the confirmation in the bank's app. Takes no code. */
    String completeAuth(String processId);

    List<PlanData> fetchPlans(String sessionState);

    record InitiateResult(String processId, boolean mfaRequired, String mfaType, String sessionState) {}

    /**
     * One FCPE line inside a plan. Everything is EUR-denominated. Employer funds
     * usually show only the manager's fund code, no ISIN.
     */
    record Position(String isin, String fundCode, String label, BigDecimal quantity,
                    BigDecimal unitValue, BigDecimal valueEur, BigDecimal pnlEur) {}

    /** One plan -- PEG, PEE, PERCOL... -- and the funds held inside it. */
    record PlanData(String externalId, String name, String planKind, String employer,
                    BigDecimal balanceEur, List<Position> positions, boolean snapshotComplete) {}
}
