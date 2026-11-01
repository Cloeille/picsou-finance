package com.picsou.port;

import com.picsou.model.AccountType;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

/**
 * Read-only access to a CIC online-banking customer space.
 *
 * <p>PSD2 only exposes CIC's current accounts; this reads the savings accounts
 * too. The login is a plain form; the site then asks for a mobile confirmation
 * or sends a code by SMS, unless it still remembers the device.
 * {@link #initiateAuth} takes the previous session so the sidecar can replay
 * that remembered-device cookie: a re-login with stored credentials then
 * completes without anyone at the phone. The session state is opaque to the
 * domain and only ever stored encrypted.
 */
public interface CicPort {
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
     * mobile confirmation, which waits for the customer to approve in the app.
     */
    String completeAuth(String processId, String code);

    /** The current and savings accounts, balances only. Insurance and other contracts are left out by the sidecar. */
    AccountListing fetchAccounts(String sessionState);

    record InitiateResult(String processId, boolean mfaRequired, String mfaType, String sessionState) {}

    /**
     * The accounts, and the in-scope tables the page carried ({@code CHECKING}, {@code SAVINGS}),
     * an empty one included. {@code categoriesRead} is null when the sidecar does not say
     * (an older sidecar answering with a bare list).
     */
    record AccountListing(List<AccountData> accounts, Set<String> categoriesRead) {
        public AccountListing {
            accounts = List.copyOf(accounts);
            categoriesRead = categoriesRead == null ? null : Set.copyOf(categoriesRead);
        }

        /** A listing that does not say which tables were read. */
        public static AccountListing withoutCategories(List<AccountData> accounts) {
            return new AccountListing(accounts, null);
        }
    }

    record AccountData(String externalId, String name, AccountType type, BigDecimal balanceEur, boolean snapshotComplete) {}
}
