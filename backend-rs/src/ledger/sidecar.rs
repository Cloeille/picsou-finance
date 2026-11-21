//! The sidecar contract shared by the bank connectors: the error codes their sidecars return,
//! the banks that speak the strict `{"detail": "<CODE>"}` contract, the common transaction shape
//! and the per-source sync outcome.

use std::fmt;
use std::str::FromStr;

use chrono::NaiveDate;
use rust_decimal::Decimal;
use serde::{Deserialize, Serialize};

use crate::ledger::model::UnknownVariant;

/// Stable failure codes the connector sidecars return in their `detail` field, persisted on each
/// `*_session.last_sync_error` column. Variant names are the persisted and wire values: never
/// rename one. Which codes a given bank may emit is declared by [`SidecarBank`].
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, PartialOrd, Ord, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum SidecarErrorCode {
    /// The bank refused the login identifier or the password.
    InvalidCredentials,
    /// The one-time code (SMS, e-mail) was wrong; the same attempt can take another try.
    InvalidOtp,
    /// The verification code by e-mail was wrong, expired, or already used. The caller can retry
    /// with a new one: the portal itself offers to re-send.
    MfaInvalid,
    /// The pending login is unknown, used, expired or belongs to someone else.
    AuthAttemptExpired,
    /// The persisted provider session is no longer accepted.
    SessionExpired,
    /// Nobody approved the app push or mobile confirmation in time.
    AppValidationTimeout,
    /// The saver refused the confirmation request in the app.
    AppValidationCancelled,
    CaptchaBlocked,
    /// BoursoBank parked the login on its fraud-education notice
    /// (`/infos-profil/pedagogie-fraude/...`). The credentials were accepted; the holder must tick
    /// the notice on the bank's website, then retry. Never auto-acknowledged: it is a legal notice.
    FraudAckRequired,
    /// The access holds several identities (`/connexion/lister-identites`) and the sidecar could
    /// not single out the personal one, or found only business identities. Business identities are
    /// not synced.
    IdentitySelectionUnsupported,
    /// The bank asked for a second factor this connector does not drive (SMS, e-mail, Digipass,
    /// Safetrans).
    MfaTypeUnsupported,
    /// The site parked the login on a notice only the saver can act on (terms to accept, contact
    /// details to fill in). The credentials were accepted.
    ActionRequired,
    /// Fortuneo requires the user to defer or complete the MiFID investor profile.
    InvestorProfileRequired,
    /// The first visit to the client space is not finished on the portal -- a choice of
    /// communication channels or a signature to place. Only the account holder can do it.
    FirstVisitPending,
    /// No e-mail address is attached to the account, so no code can be sent. Sofidy's own advice is
    /// to contact the Service Associés.
    EmailUnreachable,
    /// The account is dormant and the portal refuses to authenticate it.
    AccountInactive,
    /// Sofidy's brute-force counter is armed. A wrong password is answered with a JSON body saying
    /// so, and this must never read as INVALID_CREDENTIALS: the fix is to wait, not to retype the
    /// password.
    RateLimited,
    /// The account holds more than one real-estate contract, so one is not unambiguously the
    /// account to sync.
    MultipleContracts,
    /// The login keypad is not the one the sidecar knows; nothing was clicked or sent.
    KeypadChanged,
    /// The user took longer than the keypad's lifetime to click the digits. Nothing was clicked.
    KeypadExpired,
    /// The clicked positions are not a valid choice on a ten-key pad (length or range).
    InvalidPositions,
    InvalidSessionState,
    /// A fetched snapshot was partial or failed financial reconciliation.
    PortfolioIncomplete,
    /// The bank changed an expected page or protocol structure.
    UpstreamFormatChanged,
    /// The bank or the isolated sidecar could not be reached.
    UpstreamUnavailable,
    /// Upstream data was present but malformed or internally inconsistent.
    InvalidData,
    /// An unexpected local failure prevented safe completion.
    InternalError,
}

impl SidecarErrorCode {
    /// Every code, in declaration order.
    pub const ALL: &'static [SidecarErrorCode] = &[
        SidecarErrorCode::InvalidCredentials,
        SidecarErrorCode::InvalidOtp,
        SidecarErrorCode::MfaInvalid,
        SidecarErrorCode::AuthAttemptExpired,
        SidecarErrorCode::SessionExpired,
        SidecarErrorCode::AppValidationTimeout,
        SidecarErrorCode::AppValidationCancelled,
        SidecarErrorCode::CaptchaBlocked,
        SidecarErrorCode::FraudAckRequired,
        SidecarErrorCode::IdentitySelectionUnsupported,
        SidecarErrorCode::MfaTypeUnsupported,
        SidecarErrorCode::ActionRequired,
        SidecarErrorCode::InvestorProfileRequired,
        SidecarErrorCode::FirstVisitPending,
        SidecarErrorCode::EmailUnreachable,
        SidecarErrorCode::AccountInactive,
        SidecarErrorCode::RateLimited,
        SidecarErrorCode::MultipleContracts,
        SidecarErrorCode::KeypadChanged,
        SidecarErrorCode::KeypadExpired,
        SidecarErrorCode::InvalidPositions,
        SidecarErrorCode::InvalidSessionState,
        SidecarErrorCode::PortfolioIncomplete,
        SidecarErrorCode::UpstreamFormatChanged,
        SidecarErrorCode::UpstreamUnavailable,
        SidecarErrorCode::InvalidData,
        SidecarErrorCode::InternalError,
    ];

    /// The persisted and wire name (Java's `name()`).
    pub fn as_str(self) -> &'static str {
        match self {
            SidecarErrorCode::InvalidCredentials => "INVALID_CREDENTIALS",
            SidecarErrorCode::InvalidOtp => "INVALID_OTP",
            SidecarErrorCode::MfaInvalid => "MFA_INVALID",
            SidecarErrorCode::AuthAttemptExpired => "AUTH_ATTEMPT_EXPIRED",
            SidecarErrorCode::SessionExpired => "SESSION_EXPIRED",
            SidecarErrorCode::AppValidationTimeout => "APP_VALIDATION_TIMEOUT",
            SidecarErrorCode::AppValidationCancelled => "APP_VALIDATION_CANCELLED",
            SidecarErrorCode::CaptchaBlocked => "CAPTCHA_BLOCKED",
            SidecarErrorCode::FraudAckRequired => "FRAUD_ACK_REQUIRED",
            SidecarErrorCode::IdentitySelectionUnsupported => "IDENTITY_SELECTION_UNSUPPORTED",
            SidecarErrorCode::MfaTypeUnsupported => "MFA_TYPE_UNSUPPORTED",
            SidecarErrorCode::ActionRequired => "ACTION_REQUIRED",
            SidecarErrorCode::InvestorProfileRequired => "INVESTOR_PROFILE_REQUIRED",
            SidecarErrorCode::FirstVisitPending => "FIRST_VISIT_PENDING",
            SidecarErrorCode::EmailUnreachable => "EMAIL_UNREACHABLE",
            SidecarErrorCode::AccountInactive => "ACCOUNT_INACTIVE",
            SidecarErrorCode::RateLimited => "RATE_LIMITED",
            SidecarErrorCode::MultipleContracts => "MULTIPLE_CONTRACTS",
            SidecarErrorCode::KeypadChanged => "KEYPAD_CHANGED",
            SidecarErrorCode::KeypadExpired => "KEYPAD_EXPIRED",
            SidecarErrorCode::InvalidPositions => "INVALID_POSITIONS",
            SidecarErrorCode::InvalidSessionState => "INVALID_SESSION_STATE",
            SidecarErrorCode::PortfolioIncomplete => "PORTFOLIO_INCOMPLETE",
            SidecarErrorCode::UpstreamFormatChanged => "UPSTREAM_FORMAT_CHANGED",
            SidecarErrorCode::UpstreamUnavailable => "UPSTREAM_UNAVAILABLE",
            SidecarErrorCode::InvalidData => "INVALID_DATA",
            SidecarErrorCode::InternalError => "INTERNAL_ERROR",
        }
    }
}

impl fmt::Display for SidecarErrorCode {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(self.as_str())
    }
}

impl FromStr for SidecarErrorCode {
    type Err = UnknownVariant;

    fn from_str(value: &str) -> Result<Self, UnknownVariant> {
        SidecarErrorCode::ALL
            .iter()
            .copied()
            .find(|code| code.as_str() == value)
            .ok_or_else(|| UnknownVariant {
                kind: "SidecarErrorCode",
                value: value.to_owned(),
            })
    }
}

/// A code the bank never declared, refused because its session table's CHECK would reject it.
#[derive(Debug, Clone, Copy, PartialEq, Eq, thiserror::Error)]
#[error("{bank} does not declare {code}")]
pub struct UndeclaredCode {
    pub bank: SidecarBank,
    pub code: SidecarErrorCode,
}

/// The connectors whose sidecar speaks the strict `{"detail": "<CODE>"}` contract, with what differs
/// between them: the bank's name, its `app.sidecars.<name>` entry, the error codes it may emit and
/// the user-facing wording of each. DEGIRO and Revolut stay out: their sidecars do not use the
/// `detail`-code contract.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, PartialOrd, Ord, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum SidecarBank {
    Amex,
    Amundi,
    /// Same BPCE platform and sidecar contract as `CAISSE_EPARGNE`, so the same codes.
    BanquePopulaire,
    /// BNP Paribas and Hello bank! (same platform, other base URL). The customer number opens an
    /// attempt, the member clicks the six digits on the bank's shuffled keypad, then confirms the
    /// strong authentication (Clé Digitale app or SMS code). No password or code is ever stored.
    Bnp,
    BourseDirect,
    /// There is no `INVALID_OTP`: BoursoBank's only supported second factor is an app push, so no
    /// code is ever submitted.
    Bourso,
    /// The codes from `INVALID_CREDENTIALS` to `INVALID_POSITIONS`, bar `SESSION_EXPIRED` and
    /// `INVALID_SESSION_STATE`, belong to the browser login (`/initiate`, `/keypad`, `/complete`).
    /// They can never deactivate a stored session: a failed login says nothing about the session
    /// already held.
    CaisseEpargne,
    /// The sign-in is approved in the bank's mobile app: there is no `INVALID_OTP`, because no code
    /// is submitted. Certicode is no longer the flow for a modern account.
    Lbp,
    /// The second factor is a mobile confirmation or an SMS code, depending on how the saver's
    /// access is set up.
    Cic,
    /// The second factor is a mobile confirmation or an SMS code, depending on how the saver's
    /// access is set up.
    Cmes,
    Corum,
    Fortuneo,
    /// Natixis always asks for a second factor (SMS, voice call or e-mail code), so no stored
    /// password can log in again unattended: an expired session waits for the saver to reconnect.
    Natixis,
    Sofidy,
    /// The sidecar logs in with the phone number and PIN, then waits for an in-app confirmation (or
    /// a 4-digit code when TR falls back to the v1 login). It stores no password: the session state
    /// holds only the refresh cookie, the device cookie and the subject. `INVALID_SESSION_STATE`
    /// ends the session, as a refresh cookie the sidecar cannot read can never be renewed.
    TradeRepublic,
}

impl SidecarBank {
    /// Every bank, in declaration order.
    pub const ALL: &'static [SidecarBank] = &[
        SidecarBank::Amex,
        SidecarBank::Amundi,
        SidecarBank::BanquePopulaire,
        SidecarBank::Bnp,
        SidecarBank::BourseDirect,
        SidecarBank::Bourso,
        SidecarBank::CaisseEpargne,
        SidecarBank::Lbp,
        SidecarBank::Cic,
        SidecarBank::Cmes,
        SidecarBank::Corum,
        SidecarBank::Fortuneo,
        SidecarBank::Natixis,
        SidecarBank::Sofidy,
        SidecarBank::TradeRepublic,
    ];

    /// The Java constant name.
    pub fn as_str(self) -> &'static str {
        match self {
            SidecarBank::Amex => "AMEX",
            SidecarBank::Amundi => "AMUNDI",
            SidecarBank::BanquePopulaire => "BANQUE_POPULAIRE",
            SidecarBank::Bnp => "BNP",
            SidecarBank::BourseDirect => "BOURSE_DIRECT",
            SidecarBank::Bourso => "BOURSO",
            SidecarBank::CaisseEpargne => "CAISSE_EPARGNE",
            SidecarBank::Lbp => "LBP",
            SidecarBank::Cic => "CIC",
            SidecarBank::Cmes => "CMES",
            SidecarBank::Corum => "CORUM",
            SidecarBank::Fortuneo => "FORTUNEO",
            SidecarBank::Natixis => "NATIXIS",
            SidecarBank::Sofidy => "SOFIDY",
            SidecarBank::TradeRepublic => "TRADE_REPUBLIC",
        }
    }

    /// How the bank is named to the user and in logs ("BoursoBank", "Bourse Direct").
    pub fn label(self) -> &'static str {
        match self {
            SidecarBank::Amex => "Amex",
            SidecarBank::Amundi => "Amundi",
            SidecarBank::BanquePopulaire => "Banque Populaire",
            SidecarBank::Bnp => "BNP Paribas",
            SidecarBank::BourseDirect => "Bourse Direct",
            SidecarBank::Bourso => "BoursoBank",
            SidecarBank::CaisseEpargne => "Caisse d'Epargne",
            SidecarBank::Lbp => "La Banque Postale",
            SidecarBank::Cic => "CIC",
            SidecarBank::Cmes => "Crédit Mutuel Épargne Salariale",
            SidecarBank::Corum => "CORUM",
            SidecarBank::Fortuneo => "Fortuneo",
            SidecarBank::Natixis => "Natixis Interépargne",
            SidecarBank::Sofidy => "Sofidy",
            SidecarBank::TradeRepublic => "Trade Republic",
        }
    }

    /// The `app.sidecars.<name>` key the sidecar's base URL is read from.
    pub fn service_name(self) -> &'static str {
        match self {
            SidecarBank::Amex => "amex-auth",
            SidecarBank::Amundi => "amundi-auth",
            SidecarBank::BanquePopulaire => "banque-populaire-auth",
            SidecarBank::Bnp => "bnp-auth",
            SidecarBank::BourseDirect => "bourse-direct-auth",
            SidecarBank::Bourso => "bourso-auth",
            SidecarBank::CaisseEpargne => "caisse-epargne-auth",
            SidecarBank::Lbp => "lbp-auth",
            SidecarBank::Cic => "cic-auth",
            SidecarBank::Cmes => "cmes-auth",
            SidecarBank::Corum => "corum-auth",
            SidecarBank::Fortuneo => "fortuneo-auth",
            SidecarBank::Natixis => "natixis-auth",
            SidecarBank::Sofidy => "sofidy-auth",
            SidecarBank::TradeRepublic => "tr-auth",
        }
    }

    /// Whether this bank's sessions live in `sidecar_session`; La Banque Postale keeps its own
    /// `lbp_session` table.
    pub fn stored_in_sidecar_session(self) -> bool {
        self != SidecarBank::Lbp
    }

    /// The codes this bank's sidecar declares, in `SidecarErrorCode` order (as Java's EnumSet).
    pub fn codes(self) -> Vec<SidecarErrorCode> {
        SidecarErrorCode::ALL
            .iter()
            .copied()
            .filter(|code| self.declares(*code))
            .collect()
    }

    pub fn declares(self, code: SidecarErrorCode) -> bool {
        self.messages()
            .iter()
            .any(|(declared, _)| *declared == code)
    }

    /// Guards a write to this bank's `last_sync_error`, whose CHECK accepts only [`Self::codes`].
    pub fn require_declared(
        self,
        code: SidecarErrorCode,
    ) -> Result<SidecarErrorCode, UndeclaredCode> {
        if self.declares(code) {
            Ok(code)
        } else {
            Err(UndeclaredCode { bank: self, code })
        }
    }

    /// The code named by `detail`, or None when it is unknown or this bank does not declare it.
    pub fn parse(self, detail: Option<&str>) -> Option<SidecarErrorCode> {
        let code = detail?.parse().ok()?;
        self.declares(code).then_some(code)
    }

    /// The user-facing wording for one of this bank's codes.
    pub fn message(self, code: SidecarErrorCode) -> Result<&'static str, UndeclaredCode> {
        self.messages()
            .iter()
            .find(|(declared, _)| *declared == code)
            .map(|(_, message)| *message)
            .ok_or(UndeclaredCode { bank: self, code })
    }

    fn messages(self) -> &'static [(SidecarErrorCode, &'static str)] {
        use SidecarErrorCode::*;
        match self {
            SidecarBank::Amex => &[
                (InvalidCredentials, "Amex rejected the username or password"),
                (InvalidOtp, "Amex rejected the verification code"),
                (
                    AuthAttemptExpired,
                    "The Amex authentication attempt expired",
                ),
                (SessionExpired, "The Amex session expired"),
                (UpstreamFormatChanged, "The Amex website format changed"),
                (InvalidData, "Amex returned invalid account data"),
                (UpstreamUnavailable, "Amex is temporarily unavailable"),
                (InternalError, "Amex is temporarily unavailable"),
            ],
            SidecarBank::Amundi => &[
                (InvalidCredentials, "Amundi rejected the credentials"),
                (
                    CaptchaBlocked,
                    "Amundi asked for a captcha Picsou cannot solve",
                ),
                (InvalidOtp, "Amundi rejected the verification code"),
                (
                    AppValidationTimeout,
                    "The Amundi app validation was not confirmed in time",
                ),
                (
                    AuthAttemptExpired,
                    "The Amundi authentication attempt expired",
                ),
                (SessionExpired, "The Amundi session expired"),
                (
                    PortfolioIncomplete,
                    "Amundi returned incomplete savings plans",
                ),
                (UpstreamFormatChanged, "The Amundi website format changed"),
                (InvalidData, "Amundi returned invalid savings plan data"),
                (UpstreamUnavailable, "Amundi is temporarily unavailable"),
                (InternalError, "Amundi is temporarily unavailable"),
            ],
            SidecarBank::BanquePopulaire => &[
                (SessionExpired, "The Banque Populaire session expired"),
                (
                    InvalidSessionState,
                    "The stored Banque Populaire session is unusable",
                ),
                (
                    InvalidCredentials,
                    "Banque Populaire refused the identifier or password",
                ),
                (
                    KeypadExpired,
                    "The Banque Populaire keypad expired. Start again.",
                ),
                (
                    InvalidPositions,
                    "The keypad choice is not valid. Start again.",
                ),
                (
                    KeypadChanged,
                    "Banque Populaire changed its login page. Nothing was sent.",
                ),
                (
                    AppValidationTimeout,
                    "The sign-in was not approved in Sécur'Pass in time",
                ),
                (
                    AuthAttemptExpired,
                    "This Banque Populaire sign-in attempt expired. Start again.",
                ),
                (
                    UpstreamFormatChanged,
                    "The Banque Populaire data format changed",
                ),
                (
                    UpstreamUnavailable,
                    "Banque Populaire is temporarily unavailable",
                ),
                (InternalError, "Banque Populaire is temporarily unavailable"),
            ],
            SidecarBank::Bnp => &[
                (
                    SessionExpired,
                    "The BNP Paribas session expired, reconnect to resume syncing",
                ),
                (
                    InvalidSessionState,
                    "The stored BNP Paribas session is unusable",
                ),
                (
                    InvalidCredentials,
                    "BNP Paribas refused the customer number or the code",
                ),
                (
                    KeypadExpired,
                    "The BNP Paribas keypad expired. Start again.",
                ),
                (
                    InvalidPositions,
                    "The keypad choice is not valid. Start again.",
                ),
                (
                    KeypadChanged,
                    "BNP Paribas changed its login page. Nothing was sent.",
                ),
                (
                    MfaTypeUnsupported,
                    "BNP Paribas asked for a strong authentication Picsou does not support",
                ),
                (InvalidOtp, "BNP Paribas rejected the verification code"),
                (
                    AppValidationTimeout,
                    "The sign-in was not approved in the Clé Digitale app in time",
                ),
                (
                    AuthAttemptExpired,
                    "This BNP Paribas sign-in attempt expired. Start again.",
                ),
                (
                    RateLimited,
                    "BNP Paribas locked or delayed further attempts, try again later",
                ),
                (
                    ActionRequired,
                    "BNP Paribas asks for an action on its website before Picsou can sign in",
                ),
                (
                    PortfolioIncomplete,
                    "BNP Paribas returned an incomplete portfolio",
                ),
                (UpstreamFormatChanged, "The BNP Paribas data format changed"),
                (InvalidData, "BNP Paribas returned invalid account data"),
                (
                    UpstreamUnavailable,
                    "BNP Paribas is temporarily unavailable",
                ),
                (InternalError, "BNP Paribas is temporarily unavailable"),
            ],
            SidecarBank::BourseDirect => &[
                (InvalidCredentials, "Bourse Direct rejected the credentials"),
                (InvalidOtp, "Bourse Direct rejected the verification code"),
                (
                    AuthAttemptExpired,
                    "The Bourse Direct authentication attempt expired",
                ),
                (SessionExpired, "The Bourse Direct session expired"),
                (
                    PortfolioIncomplete,
                    "Bourse Direct returned an incomplete portfolio",
                ),
                (
                    UpstreamFormatChanged,
                    "The Bourse Direct website format changed",
                ),
                (InvalidData, "Bourse Direct returned invalid portfolio data"),
                (
                    UpstreamUnavailable,
                    "Bourse Direct is temporarily unavailable",
                ),
                (InternalError, "Bourse Direct is temporarily unavailable"),
            ],
            SidecarBank::Bourso => &[
                (
                    InvalidCredentials,
                    "BoursoBank rejected the customer number or password",
                ),
                (
                    FraudAckRequired,
                    "BoursoBank needs you to log in on their website and validate the fraud-prevention notice once, then retry",
                ),
                (
                    IdentitySelectionUnsupported,
                    "BoursoBank lists several identities on this access and Picsou could not pick the personal one",
                ),
                (
                    MfaTypeUnsupported,
                    "BoursoBank asked for an SMS or e-mail code, which Picsou cannot handle. Switch your BoursoBank security settings to app validation.",
                ),
                (
                    AppValidationTimeout,
                    "The BoursoBank app validation was not confirmed in time",
                ),
                (
                    AuthAttemptExpired,
                    "The BoursoBank authentication attempt expired",
                ),
                (SessionExpired, "The BoursoBank session expired"),
                (
                    PortfolioIncomplete,
                    "BoursoBank returned an incomplete portfolio",
                ),
                (
                    UpstreamFormatChanged,
                    "The BoursoBank website format changed",
                ),
                (InvalidData, "BoursoBank returned invalid account data"),
                (UpstreamUnavailable, "BoursoBank is temporarily unavailable"),
                (InternalError, "BoursoBank is temporarily unavailable"),
            ],
            SidecarBank::CaisseEpargne => &[
                (SessionExpired, "The Caisse d'Epargne session expired"),
                (
                    InvalidSessionState,
                    "The stored Caisse d'Epargne session is unusable",
                ),
                (
                    InvalidCredentials,
                    "Caisse d'Epargne refused the identifier or password",
                ),
                (
                    KeypadExpired,
                    "The Caisse d'Epargne keypad expired. Start again.",
                ),
                (
                    InvalidPositions,
                    "The keypad choice is not valid. Start again.",
                ),
                (
                    KeypadChanged,
                    "Caisse d'Epargne changed its login page. Nothing was sent.",
                ),
                (
                    AppValidationTimeout,
                    "The sign-in was not approved in Sécur'Pass in time",
                ),
                (
                    AuthAttemptExpired,
                    "This Caisse d'Epargne sign-in attempt expired. Start again.",
                ),
                (
                    UpstreamFormatChanged,
                    "The Caisse d'Epargne data format changed",
                ),
                (
                    UpstreamUnavailable,
                    "Caisse d'Epargne is temporarily unavailable",
                ),
                (InternalError, "Caisse d'Epargne is temporarily unavailable"),
            ],
            SidecarBank::Lbp => &[
                (SessionExpired, "The La Banque Postale session expired"),
                (
                    InvalidSessionState,
                    "The stored La Banque Postale session is unusable",
                ),
                (
                    InvalidCredentials,
                    "La Banque Postale refused the identifier or password",
                ),
                (
                    KeypadExpired,
                    "The La Banque Postale keypad expired. Start again.",
                ),
                (
                    InvalidPositions,
                    "The keypad choice is not valid. Start again.",
                ),
                (
                    KeypadChanged,
                    "La Banque Postale changed its login page. Nothing was sent.",
                ),
                (
                    AppValidationTimeout,
                    "The sign-in was not approved in the mobile app in time",
                ),
                (
                    AuthAttemptExpired,
                    "This La Banque Postale sign-in attempt expired. Start again.",
                ),
                (
                    UpstreamFormatChanged,
                    "The La Banque Postale data format changed",
                ),
                (
                    UpstreamUnavailable,
                    "La Banque Postale is temporarily unavailable",
                ),
                (
                    InternalError,
                    "La Banque Postale is temporarily unavailable",
                ),
            ],
            SidecarBank::Cic => &[
                (
                    InvalidCredentials,
                    "CIC rejected the customer number or password",
                ),
                (
                    ActionRequired,
                    "CIC needs you to log in on their website once and complete the pending step, then retry",
                ),
                (
                    MfaTypeUnsupported,
                    "CIC asked for a Digipass or Safetrans, which Picsou cannot handle. Switch your security settings to mobile confirmation or SMS.",
                ),
                (InvalidOtp, "CIC rejected the SMS code"),
                (
                    AppValidationTimeout,
                    "The mobile confirmation was not approved in time",
                ),
                (
                    AppValidationCancelled,
                    "The mobile confirmation was refused in the app",
                ),
                (AuthAttemptExpired, "The CIC authentication attempt expired"),
                (SessionExpired, "The CIC session expired"),
                (
                    PortfolioIncomplete,
                    "CIC returned an incomplete account list",
                ),
                (UpstreamFormatChanged, "The CIC website format changed"),
                (InvalidData, "CIC returned invalid account data"),
                (UpstreamUnavailable, "CIC is temporarily unavailable"),
                (InternalError, "CIC is temporarily unavailable"),
            ],
            SidecarBank::Cmes => &[
                (
                    InvalidCredentials,
                    "Crédit Mutuel Épargne Salariale rejected the login or password",
                ),
                (
                    ActionRequired,
                    "Crédit Mutuel Épargne Salariale needs you to log in on their website once and complete the pending step, then retry",
                ),
                (
                    MfaTypeUnsupported,
                    "Crédit Mutuel Épargne Salariale asked for a Digipass or Safetrans, which Picsou cannot handle. Switch your security settings to mobile confirmation or SMS.",
                ),
                (
                    InvalidOtp,
                    "Crédit Mutuel Épargne Salariale rejected the SMS code",
                ),
                (
                    AppValidationTimeout,
                    "The mobile confirmation was not approved in time",
                ),
                (
                    AppValidationCancelled,
                    "The mobile confirmation was refused in the app",
                ),
                (
                    AuthAttemptExpired,
                    "The Crédit Mutuel Épargne Salariale authentication attempt expired",
                ),
                (
                    SessionExpired,
                    "The Crédit Mutuel Épargne Salariale session expired",
                ),
                (
                    PortfolioIncomplete,
                    "Crédit Mutuel Épargne Salariale returned incomplete plans",
                ),
                (
                    UpstreamFormatChanged,
                    "The Crédit Mutuel Épargne Salariale website format changed",
                ),
                (
                    InvalidData,
                    "Crédit Mutuel Épargne Salariale returned invalid plan data",
                ),
                (
                    UpstreamUnavailable,
                    "Crédit Mutuel Épargne Salariale is temporarily unavailable",
                ),
                (
                    InternalError,
                    "Crédit Mutuel Épargne Salariale is temporarily unavailable",
                ),
            ],
            SidecarBank::Corum => &[
                (InvalidCredentials, "CORUM rejected the credentials"),
                (SessionExpired, "The CORUM session expired"),
                (
                    MultipleContracts,
                    "This CORUM login holds more than one real-estate contract",
                ),
                (
                    PortfolioIncomplete,
                    "CORUM returned an incomplete portfolio",
                ),
                (UpstreamFormatChanged, "The CORUM website format changed"),
                (InvalidData, "CORUM returned invalid portfolio data"),
                (UpstreamUnavailable, "CORUM is temporarily unavailable"),
                (InternalError, "CORUM is temporarily unavailable"),
            ],
            SidecarBank::Fortuneo => &[
                (InvalidCredentials, "Fortuneo rejected the credentials"),
                (InvalidOtp, "Fortuneo rejected the verification code"),
                (
                    AuthAttemptExpired,
                    "The Fortuneo authentication attempt expired",
                ),
                (SessionExpired, "The Fortuneo session expired"),
                (
                    InvestorProfileRequired,
                    "Fortuneo requires the investor profile to be handled",
                ),
                (
                    PortfolioIncomplete,
                    "Fortuneo returned an incomplete portfolio",
                ),
                (UpstreamFormatChanged, "The Fortuneo website format changed"),
                (InvalidData, "Fortuneo returned invalid portfolio data"),
                (UpstreamUnavailable, "Fortuneo is temporarily unavailable"),
                (InternalError, "Fortuneo is temporarily unavailable"),
            ],
            SidecarBank::Natixis => &[
                (
                    InvalidCredentials,
                    "Natixis Interépargne rejected the e-mail or password",
                ),
                (
                    MfaTypeUnsupported,
                    "Natixis Interépargne only offers passkey login or an unsupported second factor for this account",
                ),
                (
                    InvalidOtp,
                    "Natixis Interépargne rejected the verification code",
                ),
                (
                    RateLimited,
                    "Natixis Interépargne blocked further attempts, try again later",
                ),
                (
                    AuthAttemptExpired,
                    "The Natixis Interépargne authentication attempt expired",
                ),
                (
                    SessionExpired,
                    "The Natixis Interépargne session expired, reconnect to resume syncing",
                ),
                (
                    PortfolioIncomplete,
                    "Natixis Interépargne returned incomplete savings plans",
                ),
                (
                    UpstreamFormatChanged,
                    "The Natixis Interépargne website format changed",
                ),
                (
                    InvalidData,
                    "Natixis Interépargne returned invalid savings plan data",
                ),
                (
                    UpstreamUnavailable,
                    "Natixis Interépargne is temporarily unavailable",
                ),
                (
                    InternalError,
                    "Natixis Interépargne is temporarily unavailable",
                ),
            ],
            SidecarBank::Sofidy => &[
                (
                    InvalidCredentials,
                    "Sofidy rejected the associate code or the password",
                ),
                (MfaInvalid, "Sofidy rejected the verification code"),
                (
                    FirstVisitPending,
                    "Finish your first visit on the Sofidy client space first",
                ),
                (
                    EmailUnreachable,
                    "No e-mail address is attached to this Sofidy account",
                ),
                (AccountInactive, "This Sofidy account is inactive"),
                (
                    RateLimited,
                    "Sofidy is rate-limiting this login, try again later",
                ),
                (
                    AuthAttemptExpired,
                    "The Sofidy authentication attempt expired",
                ),
                (SessionExpired, "The Sofidy session expired"),
                (
                    PortfolioIncomplete,
                    "Sofidy returned an incomplete portfolio",
                ),
                (UpstreamFormatChanged, "The Sofidy website format changed"),
                (InvalidData, "Sofidy returned invalid portfolio data"),
                (UpstreamUnavailable, "Sofidy is temporarily unavailable"),
                (InternalError, "Sofidy is temporarily unavailable"),
            ],
            SidecarBank::TradeRepublic => &[
                (
                    InvalidCredentials,
                    "Trade Republic rejected the phone number or PIN",
                ),
                (InvalidOtp, "Trade Republic rejected the verification code"),
                (
                    AppValidationTimeout,
                    "The Trade Republic app confirmation was not given in time",
                ),
                (
                    AppValidationCancelled,
                    "The Trade Republic app confirmation was refused",
                ),
                (
                    AuthAttemptExpired,
                    "The Trade Republic authentication attempt expired",
                ),
                (SessionExpired, "The Trade Republic session expired"),
                (
                    InvalidSessionState,
                    "The stored Trade Republic session is unusable",
                ),
                (
                    RateLimited,
                    "Trade Republic is rate-limiting this login, try again later",
                ),
                (
                    UpstreamFormatChanged,
                    "The Trade Republic API format changed",
                ),
                (InvalidData, "Trade Republic returned invalid account data"),
                (
                    UpstreamUnavailable,
                    "Trade Republic is temporarily unavailable",
                ),
                (InternalError, "Trade Republic is temporarily unavailable"),
            ],
        }
    }
}

impl fmt::Display for SidecarBank {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(self.as_str())
    }
}

impl FromStr for SidecarBank {
    type Err = UnknownVariant;

    fn from_str(value: &str) -> Result<Self, UnknownVariant> {
        SidecarBank::ALL
            .iter()
            .copied()
            .find(|bank| bank.as_str() == value)
            .ok_or_else(|| UnknownVariant {
                kind: "SidecarBank",
                value: value.to_owned(),
            })
    }
}

/// A bank-sidecar transaction in the common shape used by cash-account importers. Every field may
/// be absent; importers skip a row without date or amount.
#[derive(Debug, Clone, PartialEq, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SidecarTransaction {
    pub external_id: Option<String>,
    pub date: Option<NaiveDate>,
    pub description: Option<String>,
    pub amount: Option<Decimal>,
    pub counterparty: Option<String>,
    pub kind: Option<String>,
    /// The leg's own currency when the sidecar reports one; None means the account's. A Revolut
    /// exchange leg can be in another currency than the pocket it is listed on.
    pub currency: Option<String>,
}

/// The outcome of syncing one source.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum SyncStatus {
    Synced,
    Queued,
    SkippedNotConnected,
    NeedsReauth,
    Failed,
    Skipped,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct SourceSyncResult {
    pub source: String,
    pub status: SyncStatus,
    pub message: String,
}

impl SourceSyncResult {
    /// Classifies a failed sync of `source` from the SyncException's `code` and `message`,
    /// recognizing only provider-defined authentication or session signals. The provider message
    /// never reaches the result.
    pub fn from_sync_exception(source: &str, code: Option<&str>, message: Option<&str>) -> Self {
        let needs_reauth = match source {
            "trade-republic" => {
                matches!(code, Some("SESSION_EXPIRED" | "INVALID_SESSION_STATE"))
                    || (code.is_none() && message == Some("AUTHENTICATION_ERROR"))
            }
            "revolut" => {
                code == Some("SESSION_EXPIRED")
                    || (code.is_none() && message == Some("SESSION_EXPIRED"))
            }
            "ibkr" => {
                code.is_none()
                    && (ends_with_provider_code(message, "1012")
                        || ends_with_provider_code(message, "1015"))
            }
            "bourso" | "cmes" | "cic" | "corum" | "natixis" | "lbp" | "bnp" => {
                matches!(code, Some("SESSION_EXPIRED" | "INVALID_CREDENTIALS"))
            }
            "louve" => matches!(code, Some("SESSION_EXPIRED" | "INVALID_SESSION_STATE")),
            "bourse-direct" | "amundi" | "fortuneo" | "amex" | "simplefin" | "wise" => {
                code == Some("SESSION_EXPIRED")
            }
            _ => false,
        };
        let (status, message) = if needs_reauth {
            (SyncStatus::NeedsReauth, "Reauthentication required")
        } else {
            (SyncStatus::Failed, "Sync failed")
        };
        Self {
            source: source.to_owned(),
            status,
            message: message.to_owned(),
        }
    }
}

fn ends_with_provider_code(message: Option<&str>, code: &str) -> bool {
    message.is_some_and(|m| m.ends_with(&format!("(code {code})")))
}
