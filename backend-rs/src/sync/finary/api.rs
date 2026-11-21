//! The request and response bodies of FinaryApiSyncController, as the Java records name them.

use chrono::{DateTime, NaiveDate, Utc};
use rust_decimal::Decimal;
use serde::{Deserialize, Serialize, Serializer};
use serde_with::skip_serializing_none;

use crate::ledger::model::{Account, AccountType, CardNature, RecurringCadence};

/// A BigDecimal the Java DTO hands over as a primitive double: Jackson writes it as
/// Double.toString does (`100.0`, `1.0E7`).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct JavaDouble(pub Decimal);

impl JavaDouble {
    /// Double.toString of the value: plain notation with at least one fraction digit between
    /// 1e-3 and 1e7, computerized scientific notation outside.
    pub fn render(self) -> String {
        let value = self.0.normalize();
        if value.is_zero() {
            return "0.0".to_owned();
        }
        let sign = if value.is_sign_negative() { "-" } else { "" };
        let digits = value.mantissa().unsigned_abs().to_string();
        let exponent =
            i64::try_from(digits.len()).unwrap_or(i64::MAX) - 1 - i64::from(value.scale());
        if (-3..7).contains(&exponent) {
            let plain = value.abs().to_string();
            let plain = if plain.contains('.') {
                plain
            } else {
                format!("{plain}.0")
            };
            return format!("{sign}{plain}");
        }
        let (first, rest) = digits.split_at(1);
        let rest = rest.trim_end_matches('0');
        let rest = if rest.is_empty() { "0" } else { rest };
        format!("{sign}{first}.{rest}E{exponent}")
    }
}

/// serde_json's arbitrary-precision token: the field is written verbatim as a JSON number, as
/// rust_decimal's arbitrary_precision serializer does.
const RAW_NUMBER: &str = "$serde_json::private::Number";

impl Serialize for JavaDouble {
    fn serialize<S: Serializer>(&self, serializer: S) -> Result<S::Ok, S::Error> {
        use serde::ser::SerializeStruct;
        let mut raw = serializer.serialize_struct(RAW_NUMBER, 1)?;
        raw.serialize_field(RAW_NUMBER, &self.render())?;
        raw.end()
    }
}

#[derive(Debug, Deserialize)]
pub struct LoginRequest {
    pub email: Option<String>,
    pub password: Option<String>,
}

#[skip_serializing_none]
#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ConnectionStatusResponse {
    pub connected: bool,
    pub session_id: Option<i64>,
    pub status: Option<String>,
    pub last_synced_at: Option<DateTime<Utc>>,
    pub masked_email: Option<String>,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct CheckTotpResponse {
    pub totp_required: bool,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum MappingAction {
    Skip,
    MapExisting,
    CreateNew,
}

#[skip_serializing_none]
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct NewAccountDetails {
    pub name: Option<String>,
    #[serde(rename = "type")]
    pub account_type: Option<AccountType>,
    pub provider: Option<String>,
    pub currency: Option<String>,
    pub color: Option<String>,
}

#[skip_serializing_none]
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AccountMapping {
    pub finary_id: Option<String>,
    pub finary_name: Option<String>,
    pub finary_category: Option<String>,
    pub action: Option<MappingAction>,
    pub target_account_id: Option<i64>,
    pub new_account: Option<NewAccountDetails>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ExecuteRequest {
    pub sync_token: Option<String>,
    pub mappings: Option<Vec<AccountMapping>>,
}

#[skip_serializing_none]
#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AccountPreview {
    pub finary_id: Option<String>,
    pub finary_name: Option<String>,
    pub finary_institution: Option<String>,
    pub finary_category: String,
    pub suggested_type: AccountType,
    pub current_balance: JavaDouble,
    pub native_currency: String,
    pub transaction_count: usize,
}

#[skip_serializing_none]
#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PreviewResponse {
    pub accounts: Vec<AccountPreview>,
    pub existing_picsou_accounts: Vec<AccountResponse>,
    pub total_transaction_count: usize,
    pub file_token: String,
    pub auto_mapped: bool,
    pub suggested_mappings: Vec<AccountMapping>,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ImportedAccountSummary {
    pub id: i64,
    pub name: String,
    #[serde(rename = "type")]
    pub account_type: AccountType,
    pub current_balance: JavaDouble,
    pub color: String,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ImportResultResponse {
    pub accounts_created: usize,
    pub accounts_mapped: usize,
    pub accounts_skipped: usize,
    pub snapshots_created: usize,
    pub transactions_imported: usize,
    pub imported_accounts: Vec<ImportedAccountSummary>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum AutoSyncStatus {
    Ok,
    NeedsMapping,
    TotpRequired,
    NotConnected,
}

/// FinaryAutoSyncResponse; MemberSyncService maps the status.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct FinaryAutoSyncResponse {
    pub status: AutoSyncStatus,
    pub accounts_synced: usize,
    pub new_account_count: usize,
}

impl FinaryAutoSyncResponse {
    pub fn idle(status: AutoSyncStatus) -> Self {
        Self {
            status,
            accounts_synced: 0,
            new_account_count: 0,
        }
    }
}

/// AccountResponse.from(account, account.currentBalance): the plain projection the preview
/// lists as mapping targets, with no viewer, real-estate, debt or savings overlay.
#[skip_serializing_none]
#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AccountResponse {
    pub id: i64,
    pub name: String,
    #[serde(rename = "type")]
    pub account_type: AccountType,
    pub provider: Option<String>,
    pub currency: String,
    #[serde(with = "rust_decimal::serde::arbitrary_precision")]
    pub current_balance: Decimal,
    #[serde(with = "rust_decimal::serde::arbitrary_precision")]
    pub current_balance_eur: Decimal,
    #[serde(with = "rust_decimal::serde::arbitrary_precision_option")]
    pub cash_balance: Option<Decimal>,
    pub last_synced_at: Option<DateTime<Utc>>,
    pub is_manual: bool,
    pub color: String,
    pub ticker: Option<String>,
    pub logo_url: Option<String>,
    pub logo_key: Option<String>,
    pub created_at: DateTime<Utc>,
    pub opened_at: Option<NaiveDate>,
    #[serde(with = "rust_decimal::serde::arbitrary_precision_option")]
    pub payment_due_amount: Option<Decimal>,
    pub payment_due_date: Option<NaiveDate>,
    pub reward_points: Option<i64>,
    pub parent_account_id: Option<i64>,
    pub hidden: bool,
    pub list_collapsed: bool,
    pub card_nature: Option<CardNature>,
    pub contract: Option<AccountContractResponse>,
    pub institution: Option<InstitutionResponse>,
}

#[skip_serializing_none]
#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AccountContractResponse {
    #[serde(with = "rust_decimal::serde::arbitrary_precision_option")]
    pub paid_in_eur: Option<Decimal>,
    #[serde(with = "rust_decimal::serde::arbitrary_precision_option")]
    pub net_invested_eur: Option<Decimal>,
    #[serde(with = "rust_decimal::serde::arbitrary_precision_option")]
    pub scheduled_payment_eur: Option<Decimal>,
    pub scheduled_payment_cadence: Option<RecurringCadence>,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct InstitutionResponse {
    pub name: String,
    pub has_logo: bool,
}

fn blank(value: Option<&str>) -> bool {
    value.is_none_or(crate::ledger::sync_values::java_is_blank)
}

impl From<&Account> for AccountResponse {
    fn from(a: &Account) -> Self {
        let contract = (a.provider_paid_in_eur.is_some()
            || a.provider_invested_eur.is_some()
            || a.scheduled_payment_eur.is_some()
            || a.scheduled_payment_cadence.is_some())
        .then_some(AccountContractResponse {
            paid_in_eur: a.provider_paid_in_eur,
            net_invested_eur: a.provider_invested_eur,
            scheduled_payment_eur: a.scheduled_payment_eur,
            scheduled_payment_cadence: a.scheduled_payment_cadence,
        });
        let institution = a
            .institution_name
            .as_deref()
            .filter(|name| !blank(Some(name)))
            .map(|name| InstitutionResponse {
                name: name.to_owned(),
                has_logo: !blank(a.institution_domain.as_deref()),
            });
        Self {
            id: a.id,
            name: a.name.clone(),
            account_type: a.account_type,
            provider: a.provider.clone(),
            currency: a.currency.clone(),
            current_balance: a.current_balance,
            current_balance_eur: a.current_balance,
            cash_balance: a.cash_balance,
            last_synced_at: a.last_synced_at,
            is_manual: a.is_manual,
            color: a.color.clone(),
            ticker: a.ticker.clone(),
            logo_url: a.logo_url.clone(),
            logo_key: a.logo_key.clone(),
            created_at: a.created_at,
            opened_at: a.opened_at,
            payment_due_amount: a.payment_due_amount,
            payment_due_date: a.payment_due_date,
            reward_points: a.reward_points,
            parent_account_id: a.parent_account_id,
            hidden: a.hidden,
            list_collapsed: false,
            card_nature: a.card_nature,
            contract,
            institution,
        }
    }
}
