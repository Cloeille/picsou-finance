package com.picsou.port;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Read-only access to a Sofidy Espace Associé account.
 *
 * <p>Sofidy has no public API. Its client space is server-rendered PHP behind a
 * six-digit associate code, and after the password it always asks for a
 * verification code by e-mail, so authentication is always interactive:
 * {@link #initiateAuth} starts it and {@link #completeAuth} finishes it once
 * the user has typed the code. The state is opaque to the domain -- it is
 * whatever the sidecar needs to replay the account, and it is only ever stored
 * encrypted.
 *
 * <p>The portal publishes the withdrawal price itself: its "Valeur unitaire"
 * column is the redemption value for a capital-variable fund, which is the one
 * figure allowed to become a balance. Unlike CORUM there is no subscription
 * price and no displayed value to misread, so there is nothing else to carry.
 */
public interface SofidyPort {

    InitiateResult initiateAuth(String associateCode, String password);

    String completeAuth(String processId, String code);

    Snapshot fetchSnapshot(String sessionState);

    /**
     * A Sofidy login can open a session in one round-trip when no code is
     * required, which is why {@code sessionState} is not null in every answer.
     */
    record InitiateResult(String processId, boolean mfaRequired, String mfaType, String sessionState) {}

    /**
     * One fund line of the Sofidy portfolio. Everything is EUR: the funds are
     * French-domiciled and quoted in euros.
     *
     * <p>{@code withdrawalPrice} is the fund's redemption value and the only
     * price that may become a balance. A null value means Sofidy is not quoting
     * that fund today -- it is not a zero, and the caller must keep the previous
     * balance rather than clear it.
     *
     * <p>{@code fundCode} is Sofidy's own {@code Code_Produit} (DY for
     * SOFIDYNAMIC), the identifier an account is linked to. It is stable and
     * short where the fund's marketing name is neither.
     */
    record Holding(String fundCode, String label, BigDecimal quantity,
                   BigDecimal withdrawalPrice, BigDecimal totalEur) {

        /** Null when the withdrawal price is absent; never falls back to another price. */
        public BigDecimal withdrawalValue() {
            if (quantity == null || withdrawalPrice == null) {
                return null;
            }
            return quantity.multiply(withdrawalPrice);
        }
    }

    /**
     * One read of the Sofidy portfolio. {@code snapshotComplete} is a contract,
     * not a courtesy: a portfolio whose lines do not add up to the total Sofidy
     * itself prints is refused upstream, so a true value means every line was
     * read.
     *
     * <p>There is no envelope here, unlike CORUM: Sofidy serves one portfolio per
     * login, so the account is never ambiguous.
     */
    record Snapshot(String currency, BigDecimal totalEur, LocalDate valuationDate,
                    boolean snapshotComplete, List<Holding> holdings) {}
}
