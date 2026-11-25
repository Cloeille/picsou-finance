//! The Clerk and Finary payloads, read as Jackson reads the Java records: camelCase names unless
//! a field says otherwise, unknown fields ignored, a number accepted where a string is declared
//! and a numeric string where a double is.

use rust_decimal::Decimal;
use serde::{Deserialize, Deserializer};
use serde_json::Value;

/// Jackson's String coercion: a JSON number or boolean becomes its text.
fn lenient_string<'de, D: Deserializer<'de>>(deserializer: D) -> Result<Option<String>, D::Error> {
    Ok(match Option::<Value>::deserialize(deserializer)? {
        None | Some(Value::Null) => None,
        Some(Value::String(s)) => Some(s),
        Some(Value::Number(n)) => Some(n.to_string()),
        Some(Value::Bool(b)) => Some(b.to_string()),
        Some(_) => return Err(serde::de::Error::custom("expected a string")),
    })
}

/// A Double field as a decimal: a JSON number (its source text, exponent allowed) or a numeric
/// string, as Jackson coerces it.
fn lenient_decimal<'de, D: Deserializer<'de>>(
    deserializer: D,
) -> Result<Option<Decimal>, D::Error> {
    let text = match Option::<Value>::deserialize(deserializer)? {
        None | Some(Value::Null) => return Ok(None),
        Some(Value::Number(n)) => n.to_string(),
        Some(Value::String(s)) => s.trim().to_owned(),
        Some(_) => return Err(serde::de::Error::custom("expected a number")),
    };
    parse_decimal(&text)
        .map(Some)
        .ok_or_else(|| serde::de::Error::custom("not a decimal number"))
}

fn parse_decimal(text: &str) -> Option<Decimal> {
    if text.contains(['e', 'E']) {
        Decimal::from_scientific(text).ok()
    } else {
        text.parse().ok()
    }
}

#[derive(Debug, Deserialize)]
pub struct Envelope<T> {
    pub result: Option<T>,
}

#[derive(Debug, Default, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ClerkSignIn {
    #[serde(default, deserialize_with = "lenient_string")]
    pub id: Option<String>,
    #[serde(default, deserialize_with = "lenient_string")]
    pub status: Option<String>,
    #[serde(default, deserialize_with = "lenient_string")]
    pub created_session_id: Option<String>,
}

#[derive(Debug, Deserialize)]
pub struct ClerkSessionInfo {
    #[serde(default, deserialize_with = "lenient_string")]
    pub id: Option<String>,
}

#[derive(Debug, Deserialize)]
pub struct ClerkClient {
    pub sessions: Option<Vec<Option<ClerkSessionInfo>>>,
}

#[derive(Debug, Deserialize)]
pub struct ClerkSignInApiResponse {
    pub response: Option<ClerkSignIn>,
}

#[derive(Debug, Deserialize)]
pub struct ClerkSessionApiResponse {
    pub client: Option<ClerkClient>,
}

impl ClerkSessionApiResponse {
    pub fn first_session_id(self) -> Option<String> {
        self.client?.sessions?.into_iter().next()??.id
    }
}

#[derive(Debug, Deserialize)]
pub struct ClerkTokenResponse {
    #[serde(default, deserialize_with = "lenient_string")]
    pub jwt: Option<String>,
}

#[derive(Debug, Deserialize)]
pub struct Organization {
    #[serde(default, deserialize_with = "lenient_string")]
    pub id: Option<String>,
    pub members: Option<Vec<Option<OrgMember>>>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct OrgMember {
    #[serde(default, deserialize_with = "lenient_string")]
    pub id: Option<String>,
    #[serde(default, deserialize_with = "lenient_string")]
    pub member_type: Option<String>,
    pub user: Option<OrgUser>,
}

#[derive(Debug, Deserialize)]
pub struct OrgUser {
    #[serde(default, deserialize_with = "lenient_string")]
    pub fullname: Option<String>,
}

#[derive(Debug, Clone, Default, Deserialize)]
pub struct Institution {
    #[serde(default, deserialize_with = "lenient_string")]
    pub name: Option<String>,
}

#[derive(Debug, Clone, Default, Deserialize)]
pub struct Currency {
    #[serde(default, deserialize_with = "lenient_string")]
    pub code: Option<String>,
}

pub type Positions = Option<Vec<Option<Position>>>;

/// FinaryAccountDto: one account of a portfolio category, its positions nested.
#[derive(Debug, Clone, Default, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct FinaryAccount {
    #[serde(default, deserialize_with = "lenient_string")]
    pub id: Option<String>,
    #[serde(default, deserialize_with = "lenient_string")]
    pub name: Option<String>,
    #[serde(default, deserialize_with = "lenient_string")]
    pub slug: Option<String>,
    #[serde(default, deserialize_with = "lenient_decimal")]
    pub balance: Option<Decimal>,
    pub institution: Option<Institution>,
    pub currency: Option<Currency>,
    #[serde(
        rename = "display_balance",
        alias = "displayBalance",
        default,
        deserialize_with = "lenient_decimal"
    )]
    pub display_balance: Option<Decimal>,
    pub securities: Positions,
    pub cryptos: Positions,
    #[serde(rename = "fonds_euro", alias = "fondsEuro")]
    pub fonds_euro: Positions,
    pub fiats: Positions,
    #[serde(rename = "generic_assets", alias = "genericAssets")]
    pub generic_assets: Positions,
    pub scpis: Positions,
    #[serde(rename = "precious_metals", alias = "preciousMetals")]
    pub precious_metals: Positions,
}

impl FinaryAccount {
    pub fn currency_code(&self) -> Option<&str> {
        self.currency.as_ref()?.code.as_deref()
    }
}

/// FinaryPositionDto: display_* amounts are in the user's display currency.
#[derive(Debug, Clone, Default, Deserialize)]
pub struct Position {
    #[serde(default, deserialize_with = "lenient_string")]
    pub id: Option<String>,
    #[serde(default, deserialize_with = "lenient_string")]
    pub name: Option<String>,
    #[serde(rename = "type", default, deserialize_with = "lenient_string")]
    pub kind: Option<String>,
    #[serde(default, deserialize_with = "lenient_decimal")]
    pub quantity: Option<Decimal>,
    #[serde(
        rename = "display_buying_value",
        alias = "displayBuyingValue",
        default,
        deserialize_with = "lenient_decimal"
    )]
    pub display_buying_value: Option<Decimal>,
    #[serde(
        rename = "display_current_price",
        alias = "displayCurrentPrice",
        default,
        deserialize_with = "lenient_decimal"
    )]
    pub display_current_price: Option<Decimal>,
    #[serde(
        rename = "display_current_value",
        alias = "displayCurrentValue",
        default,
        deserialize_with = "lenient_decimal"
    )]
    pub display_current_value: Option<Decimal>,
    #[serde(
        rename = "display_unrealized_pnl",
        alias = "displayUnrealizedPnl",
        default,
        deserialize_with = "lenient_decimal"
    )]
    pub display_unrealized_pnl: Option<Decimal>,
    #[serde(
        rename = "display_current_upnl",
        alias = "displayCurrentUpnl",
        default,
        deserialize_with = "lenient_decimal"
    )]
    pub display_current_upnl: Option<Decimal>,
    pub security: Option<SecurityRef>,
    pub crypto: Option<AssetRef>,
    pub fiat: Option<AssetRef>,
}

#[derive(Debug, Clone, Default, Deserialize)]
pub struct SecurityRef {
    #[serde(default, deserialize_with = "lenient_string")]
    pub name: Option<String>,
    #[serde(default, deserialize_with = "lenient_string")]
    pub isin: Option<String>,
    #[serde(default, deserialize_with = "lenient_string")]
    pub symbol: Option<String>,
    #[serde(default, deserialize_with = "lenient_string")]
    pub slug: Option<String>,
}

#[derive(Debug, Clone, Default, Deserialize)]
pub struct AssetRef {
    #[serde(default, deserialize_with = "lenient_string")]
    pub name: Option<String>,
    #[serde(default, deserialize_with = "lenient_string")]
    pub code: Option<String>,
    #[serde(default, deserialize_with = "lenient_string")]
    pub symbol: Option<String>,
}

/// FinaryLoanDto from the dedicated /users/me/loans endpoint.
#[derive(Debug, Clone, Deserialize)]
pub struct Loan {
    #[serde(default, deserialize_with = "lenient_string")]
    pub id: Option<String>,
    #[serde(default, deserialize_with = "lenient_string")]
    pub name: Option<String>,
    #[serde(
        rename = "outstanding_amount",
        alias = "outstandingAmount",
        default,
        deserialize_with = "lenient_decimal"
    )]
    pub outstanding_amount: Option<Decimal>,
    pub currency: Option<Currency>,
    pub institution: Option<Institution>,
}

impl Loan {
    /// toLoanAccountDto: the outstanding amount, stored positive, as balance and no holdings.
    pub fn into_account(self) -> FinaryAccount {
        FinaryAccount {
            id: self.id,
            name: self.name,
            balance: self.outstanding_amount,
            institution: self.institution,
            currency: self.currency,
            ..FinaryAccount::default()
        }
    }
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Transaction {
    #[serde(default, deserialize_with = "lenient_string")]
    pub id: Option<String>,
    #[serde(default, deserialize_with = "lenient_string")]
    pub name: Option<String>,
    #[serde(default, deserialize_with = "lenient_string")]
    pub display_name: Option<String>,
    #[serde(default, deserialize_with = "lenient_string")]
    pub date: Option<String>,
    #[serde(default, deserialize_with = "lenient_decimal")]
    pub value: Option<Decimal>,
    #[serde(default, deserialize_with = "lenient_string")]
    pub transaction_type: Option<String>,
    pub category: Option<TransactionCategory>,
    pub currency: Option<Currency>,
    pub account: Option<TransactionAccount>,
}

impl Transaction {
    pub fn account_id(&self) -> Option<&str> {
        self.account.as_ref()?.id.as_deref()
    }
}

#[derive(Debug, Clone, Deserialize)]
pub struct TransactionCategory {
    #[serde(default, deserialize_with = "lenient_string")]
    pub name: Option<String>,
}

#[derive(Debug, Clone, Deserialize)]
pub struct TransactionAccount {
    #[serde(default, deserialize_with = "lenient_string")]
    pub id: Option<String>,
}
