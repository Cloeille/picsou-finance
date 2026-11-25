//! The ledger's rows and enums, as the Java entities in com.picsou.model map them.
//!
//! Enums are stored as their Java names. Two are native PostgreSQL enums (account_type,
//! category_kind): queries select them as `::text` and bind them with a cast.

use chrono::{DateTime, NaiveDate, Utc};
use rust_decimal::Decimal;
use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
#[error("unknown {kind} value {value:?}")]
pub struct UnknownVariant {
    pub kind: &'static str,
    pub value: String,
}

macro_rules! string_enum {
    ($(#[$meta:meta])* $name:ident { $($variant:ident = $text:literal),+ $(,)? }) => {
        $(#[$meta])*
        #[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, PartialOrd, Ord, Serialize, Deserialize)]
        pub enum $name {
            $(#[serde(rename = $text)] $variant),+
        }

        impl $name {
            pub const ALL: &'static [$name] = &[$($name::$variant),+];

            pub fn as_str(self) -> &'static str {
                match self {
                    $($name::$variant => $text),+
                }
            }
        }

        impl std::str::FromStr for $name {
            type Err = UnknownVariant;

            fn from_str(value: &str) -> Result<Self, UnknownVariant> {
                match value {
                    $($text => Ok($name::$variant),)+
                    _ => Err(UnknownVariant { kind: stringify!($name), value: value.to_owned() }),
                }
            }
        }

        impl sqlx::Type<sqlx::Postgres> for $name {
            fn type_info() -> sqlx::postgres::PgTypeInfo {
                <String as sqlx::Type<sqlx::Postgres>>::type_info()
            }

            fn compatible(ty: &sqlx::postgres::PgTypeInfo) -> bool {
                <String as sqlx::Type<sqlx::Postgres>>::compatible(ty)
            }
        }

        impl<'r> sqlx::Decode<'r, sqlx::Postgres> for $name {
            fn decode(value: sqlx::postgres::PgValueRef<'r>) -> Result<Self, sqlx::error::BoxDynError> {
                let text = <&str as sqlx::Decode<sqlx::Postgres>>::decode(value)?;
                Ok(text.parse::<$name>()?)
            }
        }

        impl sqlx::Encode<'_, sqlx::Postgres> for $name {
            fn encode_by_ref(
                &self,
                buf: &mut sqlx::postgres::PgArgumentBuffer,
            ) -> Result<sqlx::encode::IsNull, sqlx::error::BoxDynError> {
                <&str as sqlx::Encode<sqlx::Postgres>>::encode(self.as_str(), buf)
            }
        }

        impl std::fmt::Display for $name {
            fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
                f.write_str(self.as_str())
            }
        }

        $crate::db::impl_db_string_enum!($name);
    };
}

string_enum!(AccountType {
    Lep = "LEP",
    LivretA = "LIVRET_A",
    Ldds = "LDDS",
    LivretJeune = "LIVRET_JEUNE",
    Pel = "PEL",
    Cel = "CEL",
    Pea = "PEA",
    CompteTitres = "COMPTE_TITRES",
    Crypto = "CRYPTO",
    Checking = "CHECKING",
    Savings = "SAVINGS",
    RealEstate = "REAL_ESTATE",
    Scpi = "SCPI",
    Loan = "LOAN",
    EmployeeSavings = "EMPLOYEE_SAVINGS",
    AssuranceVie = "ASSURANCE_VIE",
    Per = "PER",
    CreditCard = "CREDIT_CARD",
    PeerLoan = "PEER_LOAN",
    PreciousMetals = "PRECIOUS_METALS",
    Brokerage = "BROKERAGE",
    Other = "OTHER",
});

impl AccountType {
    /// Holds positions rather than a balance: its value is recomputed from account_holding.
    pub fn is_investment(self) -> bool {
        use AccountType::*;
        matches!(
            self,
            Pea | CompteTitres | Crypto | AssuranceVie | Per | PreciousMetals | Brokerage
        )
    }

    /// The balance is the sum of the account's own transactions.
    pub fn is_ledger(self) -> bool {
        use AccountType::*;
        !self.is_investment() && !matches!(self, EmployeeSavings | RealEstate | Scpi | PeerLoan)
    }

    /// Money owed rather than wealth.
    pub fn is_liability(self) -> bool {
        matches!(self, AccountType::Loan | AccountType::CreditCard)
    }

    /// A dividend paid into it stays inside a wrapper the member cannot freely withdraw from.
    pub fn is_locked_wrapper(self) -> bool {
        use AccountType::*;
        matches!(self, Pea | AssuranceVie | Per | EmployeeSavings)
    }
}

string_enum!(
    /// Coarse allocation bucket of an account type.
    AssetClass {
        Current = "CURRENT",
        Savings = "SAVINGS",
        Investment = "INVESTMENT",
        Other = "OTHER",
    }
);

impl AssetClass {
    pub fn of(account_type: AccountType) -> Self {
        use AccountType::*;
        match account_type {
            Checking => Self::Current,
            Lep | Savings | LivretA | Ldds | LivretJeune | Pel | Cel => Self::Savings,
            Pea | CompteTitres | Crypto | EmployeeSavings | AssuranceVie | Per | PreciousMetals
            | Brokerage => Self::Investment,
            RealEstate | Scpi | Loan | CreditCard | PeerLoan | Other => Self::Other,
        }
    }

    pub fn tracks_contributions(self) -> bool {
        matches!(self, Self::Savings | Self::Investment)
    }
}

string_enum!(WealthTier {
    SafetyNet = "SAFETY_NET",
    RealEstate = "REAL_ESTATE",
    Equity = "EQUITY",
    Crypto = "CRYPTO",
    Alternative = "ALTERNATIVE",
});

impl WealthTier {
    pub fn of(account_type: AccountType) -> Self {
        use AccountType::*;
        match account_type {
            Checking | Savings | Lep | LivretA | Ldds | LivretJeune | Pel | Cel => Self::SafetyNet,
            RealEstate | Scpi | Loan => Self::RealEstate,
            Pea | CompteTitres | EmployeeSavings | AssuranceVie | Per | Brokerage => Self::Equity,
            Crypto => Self::Crypto,
            CreditCard | PeerLoan | Other | PreciousMetals => Self::Alternative,
        }
    }
}

string_enum!(TransactionType {
    Deposit = "DEPOSIT",
    Withdrawal = "WITHDRAWAL",
    Buy = "BUY",
    Sell = "SELL",
    Dividend = "DIVIDEND",
    Fee = "FEE",
});

string_enum!(
    /// Origin of a balance_snapshot row: MANUAL rows are typed by hand and never rebuilt.
    SnapshotSource {
        Manual = "MANUAL",
        Derived = "DERIVED",
    }
);

string_enum!(CardNature {
    ImmediateDebit = "IMMEDIATE_DEBIT",
    DeferredDebit = "DEFERRED_DEBIT",
    Credit = "CREDIT",
});

string_enum!(RecurringCadence {
    Weekly = "WEEKLY",
    Biweekly = "BIWEEKLY",
    Monthly = "MONTHLY",
    Quarterly = "QUARTERLY",
    Yearly = "YEARLY",
});

string_enum!(CategoryKind {
    Income = "INCOME",
    Expense = "EXPENSE",
    Transfer = "TRANSFER",
});

string_enum!(RuleSource {
    User = "USER",
    Auto = "AUTO",
});

string_enum!(RuleMatchType {
    Counterparty = "COUNTERPARTY",
    Keyword = "KEYWORD",
    KeywordsAll = "KEYWORDS_ALL",
    KeywordsAny = "KEYWORDS_ANY",
});

string_enum!(AliasMatchType {
    Word = "WORD",
    Phrase = "PHRASE",
});

string_enum!(PeerLoanRatePeriod {
    Annual = "ANNUAL",
    Monthly = "MONTHLY",
});

string_enum!(PeerLoanRepaymentKind {
    Principal = "PRINCIPAL",
    Interest = "INTEREST",
    WriteOff = "WRITE_OFF",
});

string_enum!(ValuationMode {
    Estimated = "ESTIMATED",
    Manual = "MANUAL",
});

string_enum!(ValuationStatus {
    Ok = "OK",
    UnsupportedArea = "UNSUPPORTED_AREA",
    NotEstimable = "NOT_ESTIMABLE",
    IncompleteData = "INCOMPLETE_DATA",
    GeocodingFailed = "GEOCODING_FAILED",
    NoComparableData = "NO_COMPARABLE_DATA",
    ProviderUnavailable = "PROVIDER_UNAVAILABLE",
});

string_enum!(ValuationConfidence {
    High = "HIGH",
    Medium = "MEDIUM",
    Low = "LOW",
});

/// The externalAccountId prefix the CORUM Life sync writes.
pub const CORUM_LIFE_EXTERNAL_ID_PREFIX: &str = "corum_life_";

/// An account row (soft-deleted rows are never loaded, as Account's @SQLRestriction).
#[derive(Debug, Clone, PartialEq, crate::db::DbFromRow)]
pub struct Account {
    pub id: i64,
    pub member_id: i64,
    pub name: String,
    #[sqlx(rename = "type")]
    pub account_type: AccountType,
    pub provider: Option<String>,
    pub currency: String,
    pub current_balance: Decimal,
    /// Cash inside an investment envelope (PEA/CTO); None for other providers.
    pub cash_balance: Option<Decimal>,
    pub last_synced_at: Option<DateTime<Utc>>,
    pub payment_due_amount: Option<Decimal>,
    pub payment_due_date: Option<NaiveDate>,
    pub card_nature: Option<CardNature>,
    pub reward_points: Option<i64>,
    pub external_account_id: Option<String>,
    pub is_manual: bool,
    pub color: String,
    pub iban: Option<String>,
    pub ticker: Option<String>,
    pub logo_url: Option<String>,
    pub logo_key: Option<String>,
    pub institution_name: Option<String>,
    pub institution_domain: Option<String>,
    pub requisition_id: Option<i64>,
    pub opened_at: Option<NaiveDate>,
    pub provider_invested_eur: Option<Decimal>,
    pub provider_paid_in_eur: Option<Decimal>,
    pub scheduled_payment_eur: Option<Decimal>,
    pub scheduled_payment_cadence: Option<RecurringCadence>,
    pub deleted_at: Option<DateTime<Utc>>,
    /// Revolut pockets only: the parent wallet account.
    pub parent_account_id: Option<i64>,
    pub hidden: bool,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
}

impl Account {
    /// A non-manual account the CORUM Life sync writes, recognised by its external id.
    pub fn is_synced_corum_life(&self) -> bool {
        !self.is_manual
            && self
                .external_account_id
                .as_deref()
                .is_some_and(|id| id.starts_with(CORUM_LIFE_EXTERNAL_ID_PREFIX))
    }
}

pub(crate) const ACCOUNT_COLUMNS: &str = "a.id, a.member_id, a.name, a.type::text AS type, a.provider, a.currency, \
    a.current_balance, a.cash_balance, a.last_synced_at, a.payment_due_amount, a.payment_due_date, a.card_nature, \
    a.reward_points, a.external_account_id, a.is_manual, a.color, a.iban, a.ticker, a.logo_url, a.logo_key, \
    a.institution_name, a.institution_domain, a.requisition_id, a.opened_at, a.provider_invested_eur, \
    a.provider_paid_in_eur, a.scheduled_payment_eur, a.scheduled_payment_cadence, a.deleted_at, \
    a.parent_account_id, a.hidden, a.created_at, a.updated_at";

pub(crate) const ACCOUNT_RETURNING: &str = "id, member_id, name, type::text AS type, provider, currency, \
    current_balance, cash_balance, last_synced_at, payment_due_amount, payment_due_date, card_nature, \
    reward_points, external_account_id, is_manual, color, iban, ticker, logo_url, logo_key, \
    institution_name, institution_domain, requisition_id, opened_at, provider_invested_eur, \
    provider_paid_in_eur, scheduled_payment_eur, scheduled_payment_cadence, deleted_at, \
    parent_account_id, hidden, created_at, updated_at";

#[derive(Debug, Clone, PartialEq, crate::db::DbFromRow)]
pub struct AccountHolding {
    pub id: i64,
    pub account_id: i64,
    pub ticker: String,
    pub name: Option<String>,
    pub quantity: Decimal,
    pub average_buy_in: Option<Decimal>,
    pub current_price: Option<Decimal>,
    /// ISO 4217 currency of current_price; average_buy_in stays EUR.
    pub quote_currency: Option<String>,
    pub provider_value_eur: Option<Decimal>,
    pub provider_price_date: Option<NaiveDate>,
    pub provider_pnl_eur: Option<Decimal>,
    pub last_synced_at: Option<DateTime<Utc>>,
    pub provider_wealth_tier: Option<WealthTier>,
    /// The instrument's ISIN, when the line has one (never set for crypto or manual symbols).
    /// The ticker names one listing; this names the instrument itself (#698).
    pub isin: Option<String>,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
}

/// `raw` trimmed and upper-cased when it is a well-formed ISIN (two letters, nine letters or
/// digits, one check digit) and not a Trade Republic crypto pseudo-ISIN (`XF000...`), else `None`:
/// the shape `ck_account_holding_isin` enforces, so a malformed or synthetic identifier is stored
/// as no ISIN and never fails a save.
pub fn normalize_isin(raw: Option<&str>) -> Option<String> {
    let upper = raw?.trim().to_ascii_uppercase();
    let b = upper.as_bytes();
    let well_formed = b.len() == 12
        && !upper.starts_with("XF000")
        && b[..2].iter().all(u8::is_ascii_uppercase)
        && b[2..11]
            .iter()
            .all(|c| c.is_ascii_uppercase() || c.is_ascii_digit())
        && b[11].is_ascii_digit();
    well_formed.then_some(upper)
}

pub(crate) const HOLDING_COLUMNS: &str = "h.id, h.account_id, h.ticker, h.name, h.quantity, h.average_buy_in, \
    h.current_price, h.quote_currency, h.provider_value_eur, h.provider_price_date, h.provider_pnl_eur, \
    h.last_synced_at, h.provider_wealth_tier, h.isin, h.created_at, h.updated_at";

pub(crate) const HOLDING_RETURNING: &str = "id, account_id, ticker, name, quantity, average_buy_in, \
    current_price, quote_currency, provider_value_eur, provider_price_date, provider_pnl_eur, \
    last_synced_at, provider_wealth_tier, isin, created_at, updated_at";

/// A holding to write: every column but the id and audit timestamps.
#[derive(Debug, Clone, PartialEq, Default)]
pub struct HoldingDraft {
    pub ticker: String,
    pub name: Option<String>,
    pub quantity: Decimal,
    pub average_buy_in: Option<Decimal>,
    pub current_price: Option<Decimal>,
    pub quote_currency: Option<String>,
    pub provider_value_eur: Option<Decimal>,
    pub provider_price_date: Option<NaiveDate>,
    pub provider_pnl_eur: Option<Decimal>,
    pub last_synced_at: Option<DateTime<Utc>>,
    pub provider_wealth_tier: Option<WealthTier>,
    /// Already normalised (see [`normalize_isin`]).
    pub isin: Option<String>,
}

impl From<&AccountHolding> for HoldingDraft {
    fn from(h: &AccountHolding) -> Self {
        Self {
            ticker: h.ticker.clone(),
            name: h.name.clone(),
            quantity: h.quantity,
            average_buy_in: h.average_buy_in,
            current_price: h.current_price,
            quote_currency: h.quote_currency.clone(),
            provider_value_eur: h.provider_value_eur,
            provider_price_date: h.provider_price_date,
            provider_pnl_eur: h.provider_pnl_eur,
            last_synced_at: h.last_synced_at,
            provider_wealth_tier: h.provider_wealth_tier,
            isin: h.isin.clone(),
        }
    }
}

#[derive(Debug, Clone, PartialEq, crate::db::DbFromRow)]
pub struct Transaction {
    pub id: i64,
    pub account_id: i64,
    pub date: NaiveDate,
    pub description: String,
    pub amount: Decimal,
    #[sqlx(rename = "type")]
    pub kind: Option<String>,
    pub category: Option<String>,
    pub category_id: Option<i64>,
    pub category_manual: bool,
    pub counterparty: Option<String>,
    pub merchant_label: Option<String>,
    pub merchant_brand_id: Option<i64>,
    pub ai_suggested_category_id: Option<i64>,
    pub ai_confidence: Option<i32>,
    pub external_id: Option<String>,
    pub recurring_series_id: Option<i64>,
    pub native_currency: String,
    pub created_at: DateTime<Utc>,
    pub is_manual: bool,
    /// Money moving inside an investment wrapper: left out of every budget query.
    pub budget_neutral: bool,
    /// Dedup key of provider-imported rows (bank reference or content fingerprint).
    pub external_transaction_id: Option<String>,
    pub tx_type: Option<TransactionType>,
    pub ticker: Option<String>,
    pub name: Option<String>,
    pub quantity: Option<Decimal>,
    pub price_per_unit: Option<Decimal>,
    pub fees: Option<Decimal>,
}

pub(crate) const TRANSACTION_COLUMNS: &str = "t.id, t.account_id, t.date, t.description, t.amount, t.type, \
    t.category, t.category_id, t.category_manual, t.counterparty, t.merchant_label, t.merchant_brand_id, \
    t.ai_suggested_category_id, t.ai_confidence, t.external_id, t.recurring_series_id, t.native_currency, \
    t.created_at, t.is_manual, t.budget_neutral, t.external_transaction_id, t.tx_type, t.ticker, t.name, \
    t.quantity, t.price_per_unit, t.fees";

pub(crate) const TRANSACTION_RETURNING: &str = "id, account_id, date, description, amount, type, \
    category, category_id, category_manual, counterparty, merchant_label, merchant_brand_id, \
    ai_suggested_category_id, ai_confidence, external_id, recurring_series_id, native_currency, \
    created_at, is_manual, budget_neutral, external_transaction_id, tx_type, ticker, name, \
    quantity, price_per_unit, fees";

/// A transaction to insert: every column but the id.
#[derive(Debug, Clone, PartialEq)]
pub struct NewTransaction {
    pub account_id: i64,
    pub date: NaiveDate,
    pub description: String,
    pub amount: Decimal,
    pub kind: Option<String>,
    pub category: Option<String>,
    pub category_id: Option<i64>,
    pub category_manual: bool,
    pub counterparty: Option<String>,
    pub merchant_label: Option<String>,
    pub merchant_brand_id: Option<i64>,
    pub external_id: Option<String>,
    pub native_currency: String,
    pub is_manual: bool,
    pub budget_neutral: bool,
    pub external_transaction_id: Option<String>,
    pub tx_type: Option<TransactionType>,
    pub ticker: Option<String>,
    pub name: Option<String>,
    pub quantity: Option<Decimal>,
    pub price_per_unit: Option<Decimal>,
    pub fees: Option<Decimal>,
}

impl NewTransaction {
    /// The entity builder's defaults: EUR, not manual, uncategorised.
    pub fn new(
        account_id: i64,
        date: NaiveDate,
        description: impl Into<String>,
        amount: Decimal,
    ) -> Self {
        Self {
            account_id,
            date,
            description: description.into(),
            amount,
            kind: None,
            category: None,
            category_id: None,
            category_manual: false,
            counterparty: None,
            merchant_label: None,
            merchant_brand_id: None,
            external_id: None,
            native_currency: "EUR".to_owned(),
            is_manual: false,
            budget_neutral: false,
            external_transaction_id: None,
            tx_type: None,
            ticker: None,
            name: None,
            quantity: None,
            price_per_unit: None,
            fees: None,
        }
    }
}

#[derive(Debug, Clone, PartialEq, crate::db::DbFromRow)]
pub struct BalanceSnapshot {
    pub id: i64,
    pub account_id: i64,
    pub date: NaiveDate,
    /// Always EUR, at 100% of the account (shares weight reads only).
    pub balance: Decimal,
    pub invested_amount: Decimal,
    pub created_at: DateTime<Utc>,
    pub source: SnapshotSource,
    pub native_balance: Option<Decimal>,
    pub statement_entered_at: Option<DateTime<Utc>>,
}

pub(crate) const SNAPSHOT_COLUMNS: &str = "s.id, s.account_id, s.date, s.balance, s.invested_amount, \
    s.created_at, s.source, s.native_balance, s.statement_entered_at";

pub(crate) const SNAPSHOT_RETURNING: &str = "id, account_id, date, balance, invested_amount, \
    created_at, source, native_balance, statement_entered_at";

#[derive(Debug, Clone, PartialEq, crate::db::DbFromRow)]
pub struct AccountOwnership {
    pub id: i64,
    pub account_id: i64,
    pub member_id: i64,
    /// Percentage in (0, 100].
    pub share_percent: Decimal,
}

#[derive(Debug, Clone, PartialEq, crate::db::DbFromRow)]
pub struct AccountDisplayPref {
    pub id: i64,
    pub account_id: i64,
    pub member_id: i64,
    pub list_position: Option<i32>,
    pub list_collapsed: bool,
}

#[derive(Debug, Clone, PartialEq, crate::db::DbFromRow)]
pub struct FamilyMember {
    pub id: i64,
    pub display_name: String,
    pub avatar_color: String,
    #[sqlx(rename = "is_managed")]
    pub managed: bool,
    pub created_at: DateTime<Utc>,
}

#[derive(Debug, Clone, PartialEq, crate::db::DbFromRow)]
pub struct Debt {
    pub id: i64,
    pub member_id: i64,
    pub account_id: i64,
    pub linked_account_id: Option<i64>,
    pub borrowed_amount: Decimal,
    pub interest_rate: Option<Decimal>,
    pub monthly_payment: Option<Decimal>,
    pub lender_name: Option<String>,
    pub start_date: Option<NaiveDate>,
    pub end_date: Option<NaiveDate>,
    pub insurance_monthly: Option<Decimal>,
    pub file_fees: Option<Decimal>,
}

#[derive(Debug, Clone, PartialEq, crate::db::DbFromRow)]
pub struct PeerLoanTerms {
    pub id: i64,
    pub account_id: i64,
    pub counterparty_id: i64,
    pub principal: Decimal,
    pub lent_at: NaiveDate,
    pub expected_back: Option<NaiveDate>,
    /// Always a fraction per year, whatever rate_period says.
    pub annual_rate: Option<Decimal>,
    pub rate_period: PeerLoanRatePeriod,
    pub planned_monthly_amount: Option<Decimal>,
    pub planned_months: Option<i32>,
    pub linked_account_id: Option<i64>,
    pub lending_transaction_id: Option<i64>,
    pub note: Option<String>,
    pub closed_at: Option<NaiveDate>,
}

#[derive(Debug, Clone, PartialEq, crate::db::DbFromRow)]
pub struct PeerLoanRepayment {
    pub id: i64,
    pub account_id: i64,
    pub paid_on: NaiveDate,
    pub amount: Decimal,
    pub kind: PeerLoanRepaymentKind,
    pub note: Option<String>,
    pub cash_transaction_id: Option<i64>,
    pub cash_leg_owned: bool,
}

#[derive(Debug, Clone, PartialEq, crate::db::DbFromRow)]
pub struct Category {
    pub id: i64,
    pub member_id: i64,
    pub parent_id: Option<i64>,
    pub name: String,
    pub slug: Option<String>,
    pub kind: CategoryKind,
    pub color: String,
    pub icon: Option<String>,
    pub is_default: bool,
    pub archived: bool,
    pub sort_order: i32,
}

pub(crate) const CATEGORY_COLUMNS: &str = "c.id, c.member_id, c.parent_id, c.name, c.slug, c.kind::text AS kind, \
    c.color, c.icon, c.is_default, c.archived, c.sort_order";

pub(crate) const CATEGORY_RETURNING: &str = "id, member_id, parent_id, name, slug, kind::text AS kind, \
    color, icon, is_default, archived, sort_order";

#[derive(Debug, Clone, PartialEq, crate::db::DbFromRow)]
pub struct CategorizationRule {
    pub id: i64,
    pub member_id: i64,
    pub match_type: RuleMatchType,
    pub pattern: String,
    pub category_id: i64,
    pub priority: i32,
    pub source: RuleSource,
    pub created_at: DateTime<Utc>,
}

string_enum!(AiCategorizationMode {
    Suggest = "SUGGEST",
    AutoHighConfidence = "AUTO_HIGH_CONFIDENCE",
    AutoAll = "AUTO_ALL",
});

/// A member's budget settings; absent until the member saves them.
#[derive(Debug, Clone, PartialEq, crate::db::DbFromRow)]
pub struct BudgetSettings {
    pub id: i64,
    pub member_id: i64,
    /// 1 to 28 (smallint).
    pub cycle_start_day: i16,
    pub logo_fetch_enabled: bool,
    pub ai_categorization_enabled: bool,
    pub ai_mode: AiCategorizationMode,
    pub ai_confidence_threshold: i32,
    pub updated_at: DateTime<Utc>,
}

#[derive(Debug, Clone, PartialEq, crate::db::DbFromRow)]
pub struct MerchantBrand {
    pub id: i64,
    pub slug: String,
    pub display_name: String,
    pub default_category_slug: String,
    pub color: Option<String>,
    pub monogram: Option<String>,
    pub logo_domain: Option<String>,
}

#[derive(Debug, Clone, PartialEq, crate::db::DbFromRow)]
pub struct MerchantAlias {
    pub id: i64,
    pub brand_id: i64,
    pub pattern: String,
    pub match_type: AliasMatchType,
}

#[derive(Debug, Clone, PartialEq, crate::db::DbFromRow)]
pub struct PriceSnapshot {
    pub id: i64,
    pub ticker: String,
    pub date: NaiveDate,
    pub price_eur: Decimal,
    pub created_at: DateTime<Utc>,
}

#[cfg(test)]
mod isin_tests {
    use super::normalize_isin;

    #[test]
    fn a_well_formed_isin_is_trimmed_and_upper_cased() {
        assert_eq!(
            normalize_isin(Some(" fr0000120271 ")).as_deref(),
            Some("FR0000120271")
        );
        assert_eq!(
            normalize_isin(Some("US0378331005")).as_deref(),
            Some("US0378331005")
        );
    }

    #[test]
    fn anything_else_is_no_isin() {
        for bad in [
            None,
            Some(""),
            Some("FR000012027"),
            Some("FR00001202711"),
            Some("FR000012027A"),
            Some("1R0000120271"),
            Some("FR0000120 71"),
            Some("XF000BTC0017"),
            Some(" xf000eth0015 "),
        ] {
            assert_eq!(normalize_isin(bad), None, "{bad:?}");
        }
    }
}
