//! The validation of a whole snapshot before anything is written: one doubtful account refuses
//! the lot, so a partial list can never overwrite a correct import.

use std::collections::{HashMap, HashSet};

use chrono::NaiveDate;
use rust_decimal::Decimal;
use serde::{Deserialize, Deserializer};

use super::per::{self, PerDetails};
use super::{Bank, UnsupportedContract};
use crate::AppState;
use crate::connectors::sidecar::SyncError;
use crate::connectors::sidecar::template::error;
use crate::http::json::null_as_false;
use crate::ledger::model::{AccountType, CardNature};
use crate::ledger::sidecar::SidecarErrorCode;
use crate::ledger::sync_values::{clean, limit};

/// Same width the other identified-transaction connectors use for the external id.
const MAX_EXTERNAL_ID_LENGTH: usize = 100;
const MAX_UNSUPPORTED: usize = 100;
/// The smallest amount that rounds to 10^12 at 8 places, one digit past NUMERIC(20, 8).
const DEBT_AMOUNT_BOUND: &str = "999999999999.999999995";

#[derive(Debug, Default, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct AccountsSnapshot {
    accounts: Option<Vec<AccountData>>,
    unsupported: Option<Vec<Unsupported>>,
}

#[derive(Debug, Default, Deserialize)]
#[serde(rename_all = "camelCase", default)]
struct Unsupported {
    external_id: Option<String>,
    family_code: Option<String>,
    sub_family_code: Option<String>,
}

#[derive(Debug, Default, Deserialize)]
#[serde(rename_all = "camelCase", default)]
struct AccountData {
    external_id: Option<String>,
    kind: Option<String>,
    name: Option<String>,
    balance: Option<Decimal>,
    currency: Option<String>,
    iban: Option<String>,
    #[serde(deserialize_with = "null_as_false")]
    iban_ambiguous: bool,
    card_nature: Option<String>,
    parent_external_id: Option<String>,
    next_due_date: Option<NaiveDate>,
    transactions: Option<Vec<TransactionData>>,
    #[serde(deserialize_with = "null_as_false")]
    snapshot_complete: bool,
    loan: Option<LoanDetails>,
    per: Option<PerDetails>,
}

#[derive(Debug, Default, Deserialize)]
#[serde(rename_all = "camelCase", default)]
struct TransactionData {
    external_id: Option<String>,
    date: Option<NaiveDate>,
    due_date: Option<NaiveDate>,
    amount: Option<Decimal>,
    currency: Option<String>,
    label: Option<String>,
    #[serde(deserialize_with = "scalar_text")]
    type_code: Option<String>,
}

/// A personal loan's terms as the bank states them; the rate is a fraction (0.03 for 3 %).
#[derive(Debug, Clone, Default, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct LoanDetails {
    pub borrowed_amount: Option<Decimal>,
    pub interest_rate: Option<Decimal>,
    pub monthly_payment: Option<Decimal>,
    pub insurance_monthly: Option<Decimal>,
    pub signature_date: Option<NaiveDate>,
    pub first_due_date: Option<NaiveDate>,
    pub last_due_date: Option<NaiveDate>,
    pub linked_iban: Option<String>,
}

/// The loan terms once every field the schedule needs is known to be usable.
#[derive(Debug, Clone)]
pub struct UsableLoan {
    pub borrowed_amount: Decimal,
    pub interest_rate: Decimal,
    pub monthly_payment: Decimal,
    pub insurance_monthly: Option<Decimal>,
    pub signature_date: Option<NaiveDate>,
    pub first_due_date: NaiveDate,
    pub last_due_date: NaiveDate,
    pub linked_iban: Option<String>,
}

/// Jackson reads a JSON number or boolean into a String field as its text.
fn scalar_text<'de, D: Deserializer<'de>>(deserializer: D) -> Result<Option<String>, D::Error> {
    Ok(
        match Option::<serde_json::Value>::deserialize(deserializer)? {
            None | Some(serde_json::Value::Null) => None,
            Some(serde_json::Value::String(s)) => Some(s),
            Some(v @ (serde_json::Value::Number(_) | serde_json::Value::Bool(_))) => {
                Some(v.to_string())
            }
            Some(_) => return Err(serde::de::Error::custom("expected a scalar")),
        },
    )
}

pub struct PreparedSnapshot {
    pub accounts: Vec<PreparedAccount>,
    pub unsupported: Vec<UnsupportedContract>,
}

pub struct PreparedAccount {
    pub external_id: String,
    pub name: String,
    pub account_type: AccountType,
    pub balance: Decimal,
    pub card_nature: Option<CardNature>,
    pub parent_external_id: Option<String>,
    pub next_due_date: Option<NaiveDate>,
    pub transactions: Vec<PreparedTransaction>,
    /// Never stored on the account; only used to find a loan's debited account.
    pub iban: Option<String>,
    pub iban_ambiguous: bool,
    /// LOAN only, and only when every field the schedule needs is usable.
    pub loan: Option<UsableLoan>,
    /// PER and assurance vie only, when the insurer valuation was complete.
    pub per: Option<per::Prepared>,
}

pub struct PreparedTransaction {
    pub external_id: String,
    pub date: NaiveDate,
    pub due_date: Option<NaiveDate>,
    pub amount: Decimal,
    pub label: String,
    pub type_code: Option<String>,
}

fn invalid(bank: &Bank, what: &str) -> SyncError {
    error(
        SidecarErrorCode::UpstreamFormatChanged,
        format!("{} returned {what}", bank.provider()),
    )
}

pub async fn snapshot(
    state: &AppState,
    bank: &Bank,
    fetched: AccountsSnapshot,
) -> Result<PreparedSnapshot, SyncError> {
    let raw_accounts = fetched.accounts.unwrap_or_default();
    if raw_accounts.is_empty() {
        return Err(invalid(bank, "no account"));
    }
    let mut external_ids = HashSet::new();
    let mut accounts = Vec::with_capacity(raw_accounts.len());
    for account in raw_accounts {
        if !account.snapshot_complete {
            return Err(invalid(bank, "an incomplete snapshot"));
        }
        let external_id = prefixed(
            bank,
            account.external_id.as_deref(),
            "an invalid account identifier",
        )?;
        if !external_ids.insert(external_id.clone()) {
            return Err(invalid(bank, "duplicate accounts"));
        }
        let account_type = type_of(bank, account.kind.as_deref())?;
        let Some(balance) = account.balance else {
            return Err(invalid(bank, "an account without a balance"));
        };
        if account.currency.as_deref() != Some("EUR") {
            return Err(invalid(bank, "an account that is not in EUR"));
        }
        if account_type == AccountType::CreditCard
            && balance.is_sign_positive()
            && !balance.is_zero()
        {
            // A card balance is its outstanding: money owed, never a credit.
            return Err(invalid(bank, "a card with a positive outstanding"));
        }
        if account_type == AccountType::Loan && balance.is_sign_positive() && !balance.is_zero() {
            // The bank shows a loan's outstanding capital as a negative figure.
            return Err(invalid(bank, "a loan with a positive outstanding"));
        }
        let insurer_contract = matches!(account_type, AccountType::Per | AccountType::AssuranceVie);
        if insurer_contract && balance.is_sign_negative() && !balance.is_zero() {
            return Err(invalid(bank, "an insurer contract with a negative balance"));
        }
        if account.per.is_some() && !insurer_contract {
            return Err(invalid(bank, "insurer details on another account type"));
        }
        let per = if insurer_contract {
            per::prepare(state, bank, account.per, account.currency.as_deref()).await?
        } else {
            None
        };
        let balance = match &per {
            Some(per) => per.balance,
            None if account_type == AccountType::Loan => -balance,
            None => balance,
        };
        let card_nature = if account_type == AccountType::CreditCard {
            nature_of(bank, account.card_nature.as_deref())?
        } else {
            None
        };
        let parent_external_id = match (card_nature, clean(account.parent_external_id.as_deref())) {
            (Some(_), Some(_)) => Some(prefixed(
                bank,
                account.parent_external_id.as_deref(),
                "an invalid card parent identifier",
            )?),
            _ => None,
        };
        accounts.push(PreparedAccount {
            external_id,
            name: limit(
                account.name.as_deref(),
                100,
                &fallback_name(bank, account.kind.as_deref(), account_type),
            ),
            account_type,
            balance,
            card_nature,
            parent_external_id,
            next_due_date: account.next_due_date,
            transactions: prepare_transactions(bank, account.transactions.unwrap_or_default())?,
            iban: clean(account.iban.as_deref()),
            iban_ambiguous: account.iban_ambiguous,
            loan: if account_type == AccountType::Loan {
                usable_loan(bank, account.loan)
            } else {
                None
            },
            per,
        });
    }

    let raw_unsupported = fetched.unsupported.unwrap_or_default();
    let mut unsupported = Vec::with_capacity(raw_unsupported.len());
    for item in raw_unsupported {
        if clean(item.external_id.as_deref()).is_none()
            || clean(item.family_code.as_deref()).is_none()
            || unsupported.len() >= MAX_UNSUPPORTED
        {
            return Err(invalid(bank, "an invalid unsupported contract list"));
        }
        unsupported.push(UnsupportedContract::bounded(
            item.external_id.as_deref(),
            item.family_code.as_deref(),
            item.sub_family_code.as_deref(),
        ));
    }
    Ok(PreparedSnapshot {
        accounts,
        unsupported,
    })
}

/// A provider repeating an id inside one account does not fail the sync: the last occurrence
/// wins, in the place of the first, exactly as the next sync would resolve it.
fn prepare_transactions(
    bank: &Bank,
    raw: Vec<TransactionData>,
) -> Result<Vec<PreparedTransaction>, SyncError> {
    let mut prepared: Vec<PreparedTransaction> = Vec::with_capacity(raw.len());
    let mut index: HashMap<String, usize> = HashMap::new();
    for tx in raw {
        let (Some(date), Some(amount)) = (tx.date, tx.amount) else {
            return Err(invalid(bank, "an incomplete transaction"));
        };
        if tx.currency.as_deref() != Some("EUR") {
            return Err(invalid(bank, "a transaction that is not in EUR"));
        }
        let external_id = prefixed(
            bank,
            tx.external_id.as_deref(),
            "an invalid transaction identifier",
        )?;
        let row = PreparedTransaction {
            external_id: external_id.clone(),
            date,
            due_date: tx.due_date,
            amount,
            label: limit(
                tx.label.as_deref(),
                255,
                &format!("{} transaction", bank.provider()),
            ),
            type_code: clean(tx.type_code.as_deref()),
        };
        match index.get(&external_id) {
            Some(&at) => prepared[at] = row,
            None => {
                index.insert(external_id, prepared.len());
                prepared.push(row);
            }
        }
    }
    Ok(prepared)
}

/// The loan details the schedule can use, or None: an odd field never fails the sync, the loan
/// is still imported and only its debt is left as it was. An amount the debt columns cannot hold
/// counts as odd, since PostgreSQL would refuse it and roll the whole import back.
fn usable_loan(bank: &Bank, loan: Option<LoanDetails>) -> Option<UsableLoan> {
    let loan = loan?;
    let bound: Decimal = DEBT_AMOUNT_BOUND.parse().expect("debt bound");
    let fits = |amount: Decimal| amount < bound;
    let usable = (|| {
        let borrowed_amount = loan
            .borrowed_amount
            .filter(|a| *a > Decimal::ZERO && fits(*a))?;
        let interest_rate = loan
            .interest_rate
            .filter(|r| *r >= Decimal::ZERO && *r <= Decimal::ONE)?;
        let monthly_payment = loan
            .monthly_payment
            .filter(|a| *a > Decimal::ZERO && fits(*a))?;
        if loan
            .insurance_monthly
            .is_some_and(|i| i < Decimal::ZERO || !fits(i))
        {
            return None;
        }
        let first_due_date = loan.first_due_date?;
        let last_due_date = loan.last_due_date.filter(|last| *last >= first_due_date)?;
        Some(UsableLoan {
            borrowed_amount,
            interest_rate,
            monthly_payment,
            insurance_monthly: loan.insurance_monthly,
            signature_date: loan.signature_date,
            first_due_date,
            last_due_date,
            linked_iban: loan.linked_iban.clone(),
        })
    })();
    if usable.is_none() {
        tracing::warn!(
            "{} loan details unusable; the loan's debt is left unchanged",
            bank.provider()
        );
    }
    usable
}

fn nature_of(bank: &Bank, nature: Option<&str>) -> Result<Option<CardNature>, SyncError> {
    // Never guessed: an unknown nature means the format moved under us.
    nature
        .map(|n| {
            n.parse::<CardNature>()
                .map_err(|_| invalid(bank, "an unknown card nature"))
        })
        .transpose()
}

fn type_of(bank: &Bank, kind: Option<&str>) -> Result<AccountType, SyncError> {
    let Some(kind) = kind else {
        return Err(invalid(bank, "an account without a kind"));
    };
    Ok(match kind {
        "CURRENT_ACCOUNT" => AccountType::Checking,
        "SAVINGS" | "TERM_DEPOSIT" | "COOPERATIVE_SHARES" => AccountType::Savings,
        "LIVRET_A" => AccountType::LivretA,
        "LIVRET_JEUNE" => AccountType::LivretJeune,
        "LEP" => AccountType::Lep,
        "LDDS" => AccountType::Ldds,
        "PEL" => AccountType::Pel,
        "PER" => AccountType::Per,
        "ASSURANCE_VIE" => AccountType::AssuranceVie,
        "LOAN" => AccountType::Loan,
        "CARD" => AccountType::CreditCard,
        // Never guessed: an unknown kind means the format moved under us.
        _ => return Err(invalid(bank, "an unknown account kind")),
    })
}

fn fallback_name(bank: &Bank, kind: Option<&str>, account_type: AccountType) -> String {
    match kind {
        Some("TERM_DEPOSIT") => return "Compte à terme".to_owned(),
        Some("COOPERATIVE_SHARES") => return "Parts sociales".to_owned(),
        _ => {}
    }
    let provider = bank.provider();
    match account_type {
        AccountType::CreditCard => format!("{provider} card"),
        AccountType::Savings => "Compte sur livret".to_owned(),
        AccountType::LivretA => "Livret A".to_owned(),
        AccountType::LivretJeune => "Livret Jeune".to_owned(),
        AccountType::Lep => "LEP".to_owned(),
        AccountType::Ldds => "LDDS".to_owned(),
        AccountType::Pel => "PEL".to_owned(),
        AccountType::Per => "PER".to_owned(),
        AccountType::AssuranceVie => "Assurance vie".to_owned(),
        AccountType::Loan => format!("{provider} loan"),
        _ => format!("{provider} account"),
    }
}

fn prefixed(bank: &Bank, raw: Option<&str>, problem: &str) -> Result<String, SyncError> {
    let cleaned = clean(raw).ok_or_else(|| invalid(bank, problem))?;
    let external_id = if cleaned.starts_with(bank.prefix) {
        cleaned
    } else {
        format!("{}{cleaned}", bank.prefix)
    };
    if external_id.encode_utf16().count() > MAX_EXTERNAL_ID_LENGTH {
        return Err(invalid(bank, problem));
    }
    Ok(external_id)
}
