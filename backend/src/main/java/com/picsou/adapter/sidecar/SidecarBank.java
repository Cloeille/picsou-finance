package com.picsou.adapter.sidecar;

import com.picsou.port.SidecarErrorCode;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.picsou.port.SidecarErrorCode.*;

/**
 * The connectors whose sidecar speaks the strict {@code {"detail": "<CODE>"}} contract, with what
 * differs between them: how the bank is named, which {@code app.sidecars.<name>} entry it uses,
 * which error codes it may emit and the user-facing wording of each.
 *
 * <p>The code set is per bank on purpose. Each {@code *_session.last_sync_error} column has a
 * CHECK constraint listing only that bank's codes, so a code the bank never declared must be
 * treated as unknown rather than stored. {@code SidecarBankCodesTest} pins every set to the latest
 * CHECK on its table.
 *
 * <p>DEGIRO and Revolut stay out: their sidecars do not use the {@code detail}-code contract (DEGIRO
 * relays a bare 401 for an expired session), so they take the shared {@link SidecarWebClientFactory}
 * client and keep their own mapping.
 */
public enum SidecarBank {

    AMEX("Amex", "amex-auth", Map.ofEntries(
        say(INVALID_CREDENTIALS, "Amex rejected the username or password"),
        say(INVALID_OTP, "Amex rejected the verification code"),
        say(AUTH_ATTEMPT_EXPIRED, "The Amex authentication attempt expired"),
        say(SESSION_EXPIRED, "The Amex session expired"),
        say(UPSTREAM_FORMAT_CHANGED, "The Amex website format changed"),
        say(INVALID_DATA, "Amex returned invalid account data"),
        say(UPSTREAM_UNAVAILABLE, "Amex is temporarily unavailable"),
        say(INTERNAL_ERROR, "Amex is temporarily unavailable"))),

    AMUNDI("Amundi", "amundi-auth", Map.ofEntries(
        say(INVALID_CREDENTIALS, "Amundi rejected the credentials"),
        say(CAPTCHA_BLOCKED, "Amundi asked for a captcha Picsou cannot solve"),
        say(INVALID_OTP, "Amundi rejected the verification code"),
        say(APP_VALIDATION_TIMEOUT, "The Amundi app validation was not confirmed in time"),
        say(AUTH_ATTEMPT_EXPIRED, "The Amundi authentication attempt expired"),
        say(SESSION_EXPIRED, "The Amundi session expired"),
        say(PORTFOLIO_INCOMPLETE, "Amundi returned incomplete savings plans"),
        say(UPSTREAM_FORMAT_CHANGED, "The Amundi website format changed"),
        say(INVALID_DATA, "Amundi returned invalid savings plan data"),
        say(UPSTREAM_UNAVAILABLE, "Amundi is temporarily unavailable"),
        say(INTERNAL_ERROR, "Amundi is temporarily unavailable"))),

    /** Same BPCE platform and sidecar contract as {@link #CAISSE_EPARGNE}, so the same codes. */
    BANQUE_POPULAIRE("Banque Populaire", "banque-populaire-auth", Map.ofEntries(
        say(SESSION_EXPIRED, "The Banque Populaire session expired"),
        say(INVALID_SESSION_STATE, "The stored Banque Populaire session is unusable"),
        say(INVALID_CREDENTIALS, "Banque Populaire refused the identifier or password"),
        say(KEYPAD_EXPIRED, "The Banque Populaire keypad expired. Start again."),
        say(INVALID_POSITIONS, "The keypad choice is not valid. Start again."),
        say(KEYPAD_CHANGED, "Banque Populaire changed its login page. Nothing was sent."),
        say(APP_VALIDATION_TIMEOUT, "The sign-in was not approved in Sécur'Pass in time"),
        say(AUTH_ATTEMPT_EXPIRED, "This Banque Populaire sign-in attempt expired. Start again."),
        say(UPSTREAM_FORMAT_CHANGED, "The Banque Populaire data format changed"),
        say(UPSTREAM_UNAVAILABLE, "Banque Populaire is temporarily unavailable"),
        say(INTERNAL_ERROR, "Banque Populaire is temporarily unavailable"))),

    /**
     * BNP Paribas and Hello bank! (same platform, other base URL). The customer number opens an
     * attempt, the member clicks the six digits on the bank's shuffled keypad, then confirms the
     * strong authentication (Clé Digitale app or SMS code). No password or code is ever stored.
     */
    BNP("BNP Paribas", "bnp-auth", Map.ofEntries(
        say(SESSION_EXPIRED, "The BNP Paribas session expired, reconnect to resume syncing"),
        say(INVALID_SESSION_STATE, "The stored BNP Paribas session is unusable"),
        say(INVALID_CREDENTIALS, "BNP Paribas refused the customer number or the code"),
        say(KEYPAD_EXPIRED, "The BNP Paribas keypad expired. Start again."),
        say(INVALID_POSITIONS, "The keypad choice is not valid. Start again."),
        say(KEYPAD_CHANGED, "BNP Paribas changed its login page. Nothing was sent."),
        say(MFA_TYPE_UNSUPPORTED, "BNP Paribas asked for a strong authentication Picsou does not support"),
        say(INVALID_OTP, "BNP Paribas rejected the verification code"),
        say(APP_VALIDATION_TIMEOUT, "The sign-in was not approved in the Clé Digitale app in time"),
        say(AUTH_ATTEMPT_EXPIRED, "This BNP Paribas sign-in attempt expired. Start again."),
        say(RATE_LIMITED, "BNP Paribas locked or delayed further attempts, try again later"),
        say(ACTION_REQUIRED, "BNP Paribas asks for an action on its website before Picsou can sign in"),
        say(PORTFOLIO_INCOMPLETE, "BNP Paribas returned an incomplete portfolio"),
        say(UPSTREAM_FORMAT_CHANGED, "The BNP Paribas data format changed"),
        say(INVALID_DATA, "BNP Paribas returned invalid account data"),
        say(UPSTREAM_UNAVAILABLE, "BNP Paribas is temporarily unavailable"),
        say(INTERNAL_ERROR, "BNP Paribas is temporarily unavailable"))),

    BOURSE_DIRECT("Bourse Direct", "bourse-direct-auth", Map.ofEntries(
        say(INVALID_CREDENTIALS, "Bourse Direct rejected the credentials"),
        say(INVALID_OTP, "Bourse Direct rejected the verification code"),
        say(AUTH_ATTEMPT_EXPIRED, "The Bourse Direct authentication attempt expired"),
        say(SESSION_EXPIRED, "The Bourse Direct session expired"),
        say(PORTFOLIO_INCOMPLETE, "Bourse Direct returned an incomplete portfolio"),
        say(UPSTREAM_FORMAT_CHANGED, "The Bourse Direct website format changed"),
        say(INVALID_DATA, "Bourse Direct returned invalid portfolio data"),
        say(UPSTREAM_UNAVAILABLE, "Bourse Direct is temporarily unavailable"),
        say(INTERNAL_ERROR, "Bourse Direct is temporarily unavailable"))),

    /**
     * There is no {@code INVALID_OTP}: BoursoBank's only supported second factor is an app push, so
     * no code is ever submitted.
     */
    BOURSO("BoursoBank", "bourso-auth", Map.ofEntries(
        say(INVALID_CREDENTIALS, "BoursoBank rejected the customer number or password"),
        say(FRAUD_ACK_REQUIRED,
            "BoursoBank needs you to log in on their website and validate the fraud-prevention notice once, then retry"),
        say(IDENTITY_SELECTION_UNSUPPORTED,
            "BoursoBank lists several identities on this access and Picsou could not pick the personal one"),
        say(MFA_TYPE_UNSUPPORTED,
            "BoursoBank asked for an SMS or e-mail code, which Picsou cannot handle. "
                + "Switch your BoursoBank security settings to app validation."),
        say(APP_VALIDATION_TIMEOUT, "The BoursoBank app validation was not confirmed in time"),
        say(AUTH_ATTEMPT_EXPIRED, "The BoursoBank authentication attempt expired"),
        say(SESSION_EXPIRED, "The BoursoBank session expired"),
        say(PORTFOLIO_INCOMPLETE, "BoursoBank returned an incomplete portfolio"),
        say(UPSTREAM_FORMAT_CHANGED, "The BoursoBank website format changed"),
        say(INVALID_DATA, "BoursoBank returned invalid account data"),
        say(UPSTREAM_UNAVAILABLE, "BoursoBank is temporarily unavailable"),
        say(INTERNAL_ERROR, "BoursoBank is temporarily unavailable"))),

    /**
     * The codes from {@code INVALID_CREDENTIALS} to {@code INVALID_POSITIONS}, bar {@code SESSION_EXPIRED} and
     * {@code INVALID_SESSION_STATE}, belong to the browser login ({@code /initiate}, {@code /keypad},
     * {@code /complete}). They can never deactivate a stored session: a failed login says nothing about the
     * session already held.
     */
    CAISSE_EPARGNE("Caisse d'Epargne", "caisse-epargne-auth", Map.ofEntries(
        say(SESSION_EXPIRED, "The Caisse d'Epargne session expired"),
        say(INVALID_SESSION_STATE, "The stored Caisse d'Epargne session is unusable"),
        say(INVALID_CREDENTIALS, "Caisse d'Epargne refused the identifier or password"),
        say(KEYPAD_EXPIRED, "The Caisse d'Epargne keypad expired. Start again."),
        say(INVALID_POSITIONS, "The keypad choice is not valid. Start again."),
        say(KEYPAD_CHANGED, "Caisse d'Epargne changed its login page. Nothing was sent."),
        say(APP_VALIDATION_TIMEOUT, "The sign-in was not approved in Sécur'Pass in time"),
        say(AUTH_ATTEMPT_EXPIRED, "This Caisse d'Epargne sign-in attempt expired. Start again."),
        say(UPSTREAM_FORMAT_CHANGED, "The Caisse d'Epargne data format changed"),
        say(UPSTREAM_UNAVAILABLE, "Caisse d'Epargne is temporarily unavailable"),
        say(INTERNAL_ERROR, "Caisse d'Epargne is temporarily unavailable"))),

    /**
     * The sign-in is approved in the bank's mobile app: there is no {@code INVALID_OTP}, because
     * no code is submitted. Certicode is no longer the flow for a modern account.
     */
    LBP("La Banque Postale", "lbp-auth", Map.ofEntries(
        say(SESSION_EXPIRED, "The La Banque Postale session expired"),
        say(INVALID_SESSION_STATE, "The stored La Banque Postale session is unusable"),
        say(INVALID_CREDENTIALS, "La Banque Postale refused the identifier or password"),
        say(KEYPAD_EXPIRED, "The La Banque Postale keypad expired. Start again."),
        say(INVALID_POSITIONS, "The keypad choice is not valid. Start again."),
        say(KEYPAD_CHANGED, "La Banque Postale changed its login page. Nothing was sent."),
        say(APP_VALIDATION_TIMEOUT, "The sign-in was not approved in the mobile app in time"),
        say(AUTH_ATTEMPT_EXPIRED, "This La Banque Postale sign-in attempt expired. Start again."),
        say(UPSTREAM_FORMAT_CHANGED, "The La Banque Postale data format changed"),
        say(UPSTREAM_UNAVAILABLE, "La Banque Postale is temporarily unavailable"),
        say(INTERNAL_ERROR, "La Banque Postale is temporarily unavailable"))),

    /** The second factor is a mobile confirmation or an SMS code, depending on how the saver's access is set up. */
    CIC("CIC", "cic-auth", Map.ofEntries(
        say(INVALID_CREDENTIALS, "CIC rejected the customer number or password"),
        say(ACTION_REQUIRED,
            "CIC needs you to log in on their website once and complete the pending step, then retry"),
        say(MFA_TYPE_UNSUPPORTED,
            "CIC asked for a Digipass or Safetrans, which Picsou cannot handle. "
                + "Switch your security settings to mobile confirmation or SMS."),
        say(INVALID_OTP, "CIC rejected the SMS code"),
        say(APP_VALIDATION_TIMEOUT, "The mobile confirmation was not approved in time"),
        say(APP_VALIDATION_CANCELLED, "The mobile confirmation was refused in the app"),
        say(AUTH_ATTEMPT_EXPIRED, "The CIC authentication attempt expired"),
        say(SESSION_EXPIRED, "The CIC session expired"),
        say(PORTFOLIO_INCOMPLETE, "CIC returned an incomplete account list"),
        say(UPSTREAM_FORMAT_CHANGED, "The CIC website format changed"),
        say(INVALID_DATA, "CIC returned invalid account data"),
        say(UPSTREAM_UNAVAILABLE, "CIC is temporarily unavailable"),
        say(INTERNAL_ERROR, "CIC is temporarily unavailable"))),

    /** The second factor is a mobile confirmation or an SMS code, depending on how the saver's access is set up. */
    CMES("Crédit Mutuel Épargne Salariale", "cmes-auth", Map.ofEntries(
        say(INVALID_CREDENTIALS, "Crédit Mutuel Épargne Salariale rejected the login or password"),
        say(ACTION_REQUIRED,
            "Crédit Mutuel Épargne Salariale needs you to log in on their website once and complete the pending step, then retry"),
        say(MFA_TYPE_UNSUPPORTED,
            "Crédit Mutuel Épargne Salariale asked for a Digipass or Safetrans, which Picsou cannot handle. "
                + "Switch your security settings to mobile confirmation or SMS."),
        say(INVALID_OTP, "Crédit Mutuel Épargne Salariale rejected the SMS code"),
        say(APP_VALIDATION_TIMEOUT, "The mobile confirmation was not approved in time"),
        say(APP_VALIDATION_CANCELLED, "The mobile confirmation was refused in the app"),
        say(AUTH_ATTEMPT_EXPIRED, "The Crédit Mutuel Épargne Salariale authentication attempt expired"),
        say(SESSION_EXPIRED, "The Crédit Mutuel Épargne Salariale session expired"),
        say(PORTFOLIO_INCOMPLETE, "Crédit Mutuel Épargne Salariale returned incomplete plans"),
        say(UPSTREAM_FORMAT_CHANGED, "The Crédit Mutuel Épargne Salariale website format changed"),
        say(INVALID_DATA, "Crédit Mutuel Épargne Salariale returned invalid plan data"),
        say(UPSTREAM_UNAVAILABLE, "Crédit Mutuel Épargne Salariale is temporarily unavailable"),
        say(INTERNAL_ERROR, "Crédit Mutuel Épargne Salariale is temporarily unavailable"))),

    CORUM("CORUM", "corum-auth", Map.ofEntries(
        say(INVALID_CREDENTIALS, "CORUM rejected the credentials"),
        say(SESSION_EXPIRED, "The CORUM session expired"),
        say(MULTIPLE_CONTRACTS, "This CORUM login holds more than one real-estate contract"),
        say(PORTFOLIO_INCOMPLETE, "CORUM returned an incomplete portfolio"),
        say(UPSTREAM_FORMAT_CHANGED, "The CORUM website format changed"),
        say(INVALID_DATA, "CORUM returned invalid portfolio data"),
        say(UPSTREAM_UNAVAILABLE, "CORUM is temporarily unavailable"),
        say(INTERNAL_ERROR, "CORUM is temporarily unavailable"))),

    FORTUNEO("Fortuneo", "fortuneo-auth", Map.ofEntries(
        say(INVALID_CREDENTIALS, "Fortuneo rejected the credentials"),
        say(INVALID_OTP, "Fortuneo rejected the verification code"),
        say(AUTH_ATTEMPT_EXPIRED, "The Fortuneo authentication attempt expired"),
        say(SESSION_EXPIRED, "The Fortuneo session expired"),
        say(INVESTOR_PROFILE_REQUIRED, "Fortuneo requires the investor profile to be handled"),
        say(PORTFOLIO_INCOMPLETE, "Fortuneo returned an incomplete portfolio"),
        say(UPSTREAM_FORMAT_CHANGED, "The Fortuneo website format changed"),
        say(INVALID_DATA, "Fortuneo returned invalid portfolio data"),
        say(UPSTREAM_UNAVAILABLE, "Fortuneo is temporarily unavailable"),
        say(INTERNAL_ERROR, "Fortuneo is temporarily unavailable"))),

    /**
     * Natixis always asks for a second factor (SMS, voice call or e-mail code), so no stored password
     * can log in again unattended: an expired session waits for the saver to reconnect.
     */
    NATIXIS("Natixis Interépargne", "natixis-auth", Map.ofEntries(
        say(INVALID_CREDENTIALS, "Natixis Interépargne rejected the e-mail or password"),
        say(MFA_TYPE_UNSUPPORTED,
            "Natixis Interépargne only offers passkey login or an unsupported second factor for this account"),
        say(INVALID_OTP, "Natixis Interépargne rejected the verification code"),
        say(RATE_LIMITED, "Natixis Interépargne blocked further attempts, try again later"),
        say(AUTH_ATTEMPT_EXPIRED, "The Natixis Interépargne authentication attempt expired"),
        say(SESSION_EXPIRED, "The Natixis Interépargne session expired, reconnect to resume syncing"),
        say(PORTFOLIO_INCOMPLETE, "Natixis Interépargne returned incomplete savings plans"),
        say(UPSTREAM_FORMAT_CHANGED, "The Natixis Interépargne website format changed"),
        say(INVALID_DATA, "Natixis Interépargne returned invalid savings plan data"),
        say(UPSTREAM_UNAVAILABLE, "Natixis Interépargne is temporarily unavailable"),
        say(INTERNAL_ERROR, "Natixis Interépargne is temporarily unavailable"))),

    SOFIDY("Sofidy", "sofidy-auth", Map.ofEntries(
        say(INVALID_CREDENTIALS, "Sofidy rejected the associate code or the password"),
        say(MFA_INVALID, "Sofidy rejected the verification code"),
        say(FIRST_VISIT_PENDING, "Finish your first visit on the Sofidy client space first"),
        say(EMAIL_UNREACHABLE, "No e-mail address is attached to this Sofidy account"),
        say(ACCOUNT_INACTIVE, "This Sofidy account is inactive"),
        say(RATE_LIMITED, "Sofidy is rate-limiting this login, try again later"),
        say(AUTH_ATTEMPT_EXPIRED, "The Sofidy authentication attempt expired"),
        say(SESSION_EXPIRED, "The Sofidy session expired"),
        say(PORTFOLIO_INCOMPLETE, "Sofidy returned an incomplete portfolio"),
        say(UPSTREAM_FORMAT_CHANGED, "The Sofidy website format changed"),
        say(INVALID_DATA, "Sofidy returned invalid portfolio data"),
        say(UPSTREAM_UNAVAILABLE, "Sofidy is temporarily unavailable"),
        say(INTERNAL_ERROR, "Sofidy is temporarily unavailable"))),

    /**
     * The sidecar logs in with the phone number and PIN, then waits for an in-app confirmation (or a
     * 4-digit code when TR falls back to the v1 login). It stores no password: the session state holds
     * only the refresh cookie, the device cookie and the subject. {@code INVALID_SESSION_STATE} ends
     * the session, as a refresh cookie the sidecar cannot read can never be renewed.
     */
    TRADE_REPUBLIC("Trade Republic", "tr-auth", Map.ofEntries(
        say(INVALID_CREDENTIALS, "Trade Republic rejected the phone number or PIN"),
        say(INVALID_OTP, "Trade Republic rejected the verification code"),
        say(APP_VALIDATION_TIMEOUT, "The Trade Republic app confirmation was not given in time"),
        say(APP_VALIDATION_CANCELLED, "The Trade Republic app confirmation was refused"),
        say(AUTH_ATTEMPT_EXPIRED, "The Trade Republic authentication attempt expired"),
        say(SESSION_EXPIRED, "The Trade Republic session expired"),
        say(INVALID_SESSION_STATE, "The stored Trade Republic session is unusable"),
        say(RATE_LIMITED, "Trade Republic is rate-limiting this login, try again later"),
        say(UPSTREAM_FORMAT_CHANGED, "The Trade Republic API format changed"),
        say(INVALID_DATA, "Trade Republic returned invalid account data"),
        say(UPSTREAM_UNAVAILABLE, "Trade Republic is temporarily unavailable"),
        say(INTERNAL_ERROR, "Trade Republic is temporarily unavailable")));

    private final String label;
    private final String serviceName;
    private final Map<SidecarErrorCode, String> messages;
    private final Set<SidecarErrorCode> codes;

    SidecarBank(String label, String serviceName, Map<SidecarErrorCode, String> messages) {
        this.label = label;
        this.serviceName = serviceName;
        this.messages = new EnumMap<>(messages);
        this.codes = Collections.unmodifiableSet(EnumSet.copyOf(messages.keySet()));
    }

    private static Map.Entry<SidecarErrorCode, String> say(SidecarErrorCode code, String message) {
        return Map.entry(code, message);
    }

    /** How the bank is named to the user and in logs ("BoursoBank", "Bourse Direct"). */
    public String label() {
        return label;
    }

    /** The {@code app.sidecars.<name>} key the sidecar's base URL is read from. */
    public String serviceName() {
        return serviceName;
    }

    /**
     * Whether this bank's sessions live in {@code sidecar_session}. La Banque Postale keeps its own
     * {@code lbp_session} table until it moves over in a follow-up.
     */
    public boolean storedInSidecarSession() {
        return this != LBP;
    }

    /** The codes this bank's sidecar declares, i.e. the values its session table accepts. */
    public Set<SidecarErrorCode> codes() {
        return codes;
    }

    public boolean declares(SidecarErrorCode code) {
        return code != null && codes.contains(code);
    }

    /**
     * Guards a write to this bank's {@code last_sync_error}: its CHECK constraint accepts only
     * {@link #codes()}, so an undeclared code fails here instead of at flush time.
     */
    public SidecarErrorCode requireDeclared(SidecarErrorCode code) {
        if (!declares(code)) {
            throw new IllegalArgumentException(this + " does not declare " + code);
        }
        return code;
    }

    /** The code named by {@code detail}, or empty when it is unknown or this bank does not declare it. */
    public Optional<SidecarErrorCode> parse(String detail) {
        return codes.stream().filter(code -> code.name().equals(detail)).findFirst();
    }

    /** The user-facing wording for one of this bank's codes. */
    public String message(SidecarErrorCode code) {
        String message = messages.get(code);
        if (message == null) {
            throw new IllegalArgumentException(this + " does not declare " + code);
        }
        return message;
    }
}
