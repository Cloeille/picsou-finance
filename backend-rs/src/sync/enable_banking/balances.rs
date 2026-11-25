//! fetchBalances without the HTTP: which balance of a session belongs to which account. Some ASPSPs
//! return every sibling's balances under each account uid, so a balance is only taken when the
//! session's account descriptions prove whose it is.

use std::collections::HashMap;

use rust_decimal::Decimal;
use serde::Deserialize;

use super::error::EbError;
use super::wire::{first_non_blank, lenient_string, parse_big_decimal, truncate_utf16};
use crate::error::AppError;
use crate::ledger::model::{AccountType, CardNature};
use crate::ledger::sync_values::java_trim;

const BALANCE_TYPE_PRIORITY: [&str; 5] = ["CLBD", "XPCD", "ITBD", "ITAV", "CLAV"];
/// account.name is VARCHAR(100).
const MAX_ACCOUNT_NAME_LENGTH: usize = 100;
/// ISO 20022 cash account type of a card account, as Enable Banking relays it.
const CARD_CASH_ACCOUNT_TYPE: &str = "CARD";
/// ISO 20022 cash account type of a savings account (a passbook, a Wise jar).
const SAVINGS_CASH_ACCOUNT_TYPE: &str = "SVGS";
const NO_CURRENCY: &str = "XXX";
/// ISO 20022 balance types that state credit still available, not an amount owed.
const AVAILABLE_BALANCE_TYPES: [&str; 4] = ["ITAV", "CLAV", "OPAV", "FWAV"];

#[derive(Debug, Clone, Default, Deserialize)]
pub struct AccountDetails {
    pub account_id: Option<AccountIdentification>,
    pub name: Option<String>,
    pub details: Option<String>,
    pub product: Option<String>,
    pub currency: Option<String>,
    pub cash_account_type: Option<String>,
}

#[derive(Debug, Clone, Default, Deserialize)]
pub struct AccountIdentification {
    pub iban: Option<String>,
}

#[derive(Debug, Clone, Default, Deserialize)]
pub struct BalancesResponse {
    pub balances: Option<Vec<Option<BalanceItem>>>,
}

#[derive(Debug, Clone, Default, Deserialize)]
pub struct BalanceItem {
    pub name: Option<String>,
    pub balance_amount: Option<BalanceAmount>,
    pub balance_type: Option<String>,
}

#[derive(Debug, Clone, Default, Deserialize)]
pub struct BalanceAmount {
    #[serde(default, deserialize_with = "lenient_string")]
    pub amount: Option<String>,
    pub currency: Option<String>,
}

/// BankConnectorPort.AccountData.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AccountData {
    pub external_id: String,
    pub name: Option<String>,
    pub iban: Option<String>,
    pub currency: Option<String>,
    /// None when the provider sent no balance that can be trusted: the stored one is then left as
    /// is (a new account starts at zero).
    pub balance: Option<Decimal>,
    /// What the provider says the account is; Checking when it says nothing more precise.
    pub account_type: AccountType,
    /// How a card is debited, when the provider says so; None for any other account.
    pub card_nature: Option<CardNature>,
    /// The name an earlier release would have given this account, when it differs from `name`: a
    /// stored name still equal to it was never chosen by the user and may be replaced.
    pub legacy_name: Option<String>,
}

/// Whether one account uid's balances endpoint answers for that account alone.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Scope {
    AccountScoped,
    Shared,
    Unknown,
}

impl Scope {
    /// Enable Banking documents the endpoint as account-scoped; Qonto is the reported exception
    /// that returns an organization-wide list for each account uid.
    pub fn of_aspsp(name: Option<&str>) -> Self {
        match first_non_blank(&[name]) {
            None => Self::Unknown,
            Some(n) if n.eq_ignore_ascii_case("Qonto") => Self::Shared,
            Some(_) => Self::AccountScoped,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Selection {
    AccountScoped,
    Description,
    Fallback,
}

/// The account descriptions of the session, case-insensitively counted.
struct Labels {
    claims: HashMap<String, usize>,
    complete: bool,
    scope: Scope,
}

impl Labels {
    fn count(&self, label: &str) -> usize {
        self.claims.get(&label.to_lowercase()).copied().unwrap_or(0)
    }

    fn claimed(&self, label: &str) -> bool {
        self.claims.contains_key(&label.to_lowercase())
    }
}

struct Candidates {
    details: Option<AccountDetails>,
    items: Vec<BalanceItem>,
    selection: Selection,
}

/// The two per-uid reads fetchBalances makes, each at most once (Enable Banking enforces strict
/// daily call limits).
pub(super) trait AccountFetch {
    async fn details(&self, uid: &str) -> Result<Option<AccountDetails>, EbError>;
    async fn balances(&self, uid: &str) -> Result<Option<BalancesResponse>, EbError>;
}

fn eq_ignore_case(a: &str, b: &str) -> bool {
    a == b || a.to_lowercase() == b.to_lowercase()
}

fn label_of(details: Option<&AccountDetails>) -> Option<String> {
    details.and_then(|d| first_non_blank(&[d.details.as_deref()]))
}

fn item_label(item: &BalanceItem) -> &str {
    java_trim(item.name.as_deref().unwrap_or(""))
}

fn skip(uid: &str, failure: EbError, failures: &mut Vec<EbError>) {
    tracing::warn!(
        "Failed to fetch account {} from Enable Banking; skipping it for this sync: {}",
        super::wire::fingerprint(Some(uid)),
        failure.message()
    );
    failures.push(failure);
}

/// Every usable account of the session; one account's failure only skips it, unless none is
/// usable, when the first failure is the answer.
pub(super) async fn resolve<F: AccountFetch>(
    fetch: &F,
    accounts: &[String],
    scope: Scope,
) -> Result<Vec<AccountData>, EbError> {
    let mut failures = Vec::new();
    let mut details_by_account: Vec<(String, Option<AccountDetails>)> = Vec::new();
    let mut claims: HashMap<String, usize> = HashMap::new();
    for uid in accounts {
        match fetch.details(uid).await {
            Ok(details) => {
                if let Some(label) = label_of(details.as_ref()) {
                    *claims.entry(label.to_lowercase()).or_default() += 1;
                }
                match details_by_account.iter_mut().find(|(id, _)| id == uid) {
                    Some(entry) => entry.1 = details,
                    None => details_by_account.push((uid.clone(), details)),
                }
            }
            Err(e) => skip(uid, e, &mut failures),
        }
    }
    let details_complete = details_by_account.len() == accounts.len()
        && details_by_account.iter().all(|(_, d)| d.is_some());
    let labels = Labels {
        claims,
        complete: details_complete,
        scope,
    };
    let mut candidates_by_account: Vec<(String, Candidates)> = Vec::new();
    for (uid, details) in details_by_account {
        let response = fetch.balances(&uid).await;
        match response.and_then(|r| candidates(&uid, details, r, &labels)) {
            Ok(c) => candidates_by_account.push((uid, c)),
            Err(e) => skip(&uid, e, &mut failures),
        }
    }
    let ownership_complete = details_complete && candidates_by_account.len() == accounts.len();
    let mut fetched = Vec::new();
    for (uid, c) in &candidates_by_account {
        match account_data(uid, c, &candidates_by_account, ownership_complete) {
            Ok(data) => fetched.push(data),
            Err(e) => skip(uid, e, &mut failures),
        }
    }
    if fetched.is_empty() && !failures.is_empty() {
        return Err(failures.swap_remove(0));
    }
    Ok(fetched)
}

fn candidates(
    uid: &str,
    details: Option<AccountDetails>,
    response: Option<BalancesResponse>,
    labels: &Labels,
) -> Result<Candidates, EbError> {
    let items = response.and_then(|r| r.balances).unwrap_or_default();
    tracing::info!(
        "Fetched {} balances for account {}",
        items.len(),
        super::wire::fingerprint(Some(uid))
    );
    if items.is_empty() {
        return Err(EbError::sync("Enable Banking returned no account balances"));
    }
    let Some(items) = items.into_iter().collect::<Option<Vec<_>>>() else {
        return Err(EbError::sync(
            "Enable Banking returned an invalid account balance",
        ));
    };
    let label = label_of(details.as_ref());
    let matching: Vec<BalanceItem> = items
        .iter()
        .filter(|item| {
            label.as_deref().is_some_and(|l| {
                item.name
                    .as_deref()
                    .is_some_and(|n| eq_ignore_case(l, java_trim(n)))
            })
        })
        .cloned()
        .collect();
    let matched_description = labels.complete
        && label.as_deref().is_some_and(|l| labels.count(l) == 1)
        && !matching.is_empty();
    if matched_description {
        return Ok(Candidates {
            details,
            items: matching,
            selection: Selection::Description,
        });
    }
    // Account-scoped responses may name each balance type rather than the account.
    if labels.scope == Scope::AccountScoped
        && !contains_distinct_sibling_claim(&items, label.as_deref(), labels)
    {
        return Ok(Candidates {
            details,
            items,
            selection: Selection::AccountScoped,
        });
    }
    let items = items
        .into_iter()
        .filter(|item| !labels.claimed(item_label(item)))
        .collect();
    Ok(Candidates {
        details,
        items,
        selection: Selection::Fallback,
    })
}

fn contains_distinct_sibling_claim(
    items: &[BalanceItem],
    own_label: Option<&str>,
    labels: &Labels,
) -> bool {
    let own = java_trim(own_label.unwrap_or(""));
    items
        .iter()
        .map(item_label)
        .any(|candidate| !eq_ignore_case(own, candidate) && labels.claimed(candidate))
}

fn account_data(
    uid: &str,
    c: &Candidates,
    all: &[(String, Candidates)],
    ownership_complete: bool,
) -> Result<AccountData, EbError> {
    let details = c.details.as_ref();
    // A shared balance list (fallback) cannot say which balance a card owes: imported as before.
    if let Some(details) = details
        && c.selection != Selection::Fallback
        && is_card(details)
    {
        return card_data(uid, details, &c.items);
    }
    let selected = select_balance(uid, c, all, ownership_complete)?;
    let balance = amount_of(uid, selected)?;
    let currency = first_non_blank(&[
        currency_of(selected),
        details.and_then(|d| d.currency.as_deref()),
        Some("EUR"),
    ]);
    Ok(AccountData {
        external_id: uid.to_owned(),
        name: Some(details.map_or_else(|| "Account".to_owned(), account_name)),
        iban: iban_of(details),
        currency,
        balance: Some(balance),
        account_type: details.map_or(AccountType::Checking, account_type_of),
        card_nature: None,
        legacy_name: None,
    })
}

/// A card stores what it owes, signed negative, like the BPCE and La Banque Postale cards. An
/// "available" balance (credit left) never says that, and a positive one could only be read with
/// the ASPSP's sign convention: such a card is still returned, so it stays a card and its
/// transactions import, but with no balance, which leaves the stored one untouched.
fn card_data(
    uid: &str,
    details: &AccountDetails,
    items: &[BalanceItem],
) -> Result<AccountData, EbError> {
    let owed: Vec<BalanceItem> = items
        .iter()
        .filter(|item| !is_available_balance(item))
        .cloned()
        .collect();
    // No owed balance, or two of the same rank: no single amount owed, so none is stored.
    let selected = preferred(&owed).ok();
    let balance = match selected {
        Some(item) => Some(amount_of(uid, item)?).filter(|amount| *amount <= Decimal::ZERO),
        None => None,
    };
    if balance.is_none() {
        tracing::warn!(
            "Enable Banking card {} sent no balance readable as an amount owed; its balance is left as is",
            super::wire::fingerprint(Some(uid))
        );
    }
    // ISO 4217 "XXX" (no currency) is what Boursorama declares on its cards.
    let currency = balance
        .and(selected)
        .and_then(|item| real_currency(currency_of(item)))
        .or_else(|| real_currency(details.currency.as_deref()))
        .unwrap_or_else(|| "EUR".to_owned());
    let account_name = account_name(details);
    let nature = card_nature_of(details.details.as_deref());
    // Only a known technical token in `details` (Boursorama: `immediat_debit`) is worth skipping:
    // `name` is then the label the holder sees, e.g. the brand and holder. Elsewhere `details`
    // describes the card better than the holder's name does, so the usual order stays.
    let card_name = match nature {
        None => account_name.clone(),
        Some(_) => display_name(&[
            details.name.as_deref(),
            details.product.as_deref(),
            Some("Card"),
        ]),
    };
    Ok(AccountData {
        external_id: uid.to_owned(),
        legacy_name: (account_name != card_name).then_some(account_name),
        name: Some(card_name),
        iban: iban_of(Some(details)),
        currency: Some(currency),
        balance,
        account_type: AccountType::CreditCard,
        card_nature: nature,
    })
}

fn is_card(details: &AccountDetails) -> bool {
    has_cash_account_type(details, CARD_CASH_ACCOUNT_TYPE)
}

/// A savings account the provider declares as one; any other cash account stays a current account.
fn account_type_of(details: &AccountDetails) -> AccountType {
    if has_cash_account_type(details, SAVINGS_CASH_ACCOUNT_TYPE) {
        AccountType::Savings
    } else {
        AccountType::Checking
    }
}

fn has_cash_account_type(details: &AccountDetails, code: &str) -> bool {
    eq_ignore_case(
        code,
        java_trim(details.cash_account_type.as_deref().unwrap_or("")),
    )
}

fn is_available_balance(item: &BalanceItem) -> bool {
    let kind = java_trim(item.balance_type.as_deref().unwrap_or("")).to_uppercase();
    AVAILABLE_BALANCE_TYPES.contains(&kind.as_str())
}

fn amount_of(uid: &str, item: &BalanceItem) -> Result<Decimal, EbError> {
    let amount = item
        .balance_amount
        .as_ref()
        .and_then(|a| a.amount.as_deref())
        .unwrap_or_default();
    parse_big_decimal(java_trim(amount)).ok_or_else(|| {
        EbError::Other(AppError::Internal(format!(
            "NumberFormatException: malformed Enable Banking balance amount for account {}",
            super::wire::fingerprint(Some(uid))
        )))
    })
}

fn currency_of(item: &BalanceItem) -> Option<&str> {
    item.balance_amount
        .as_ref()
        .and_then(|a| a.currency.as_deref())
}

/// The first nonblank value, cut to the account name column.
fn display_name(values: &[Option<&str>]) -> String {
    truncate_utf16(
        &first_non_blank(values).unwrap_or_default(),
        MAX_ACCOUNT_NAME_LENGTH,
    )
}

/// First nonblank of `details`, `product`, `name`: the description beats the holder.
fn account_name(details: &AccountDetails) -> String {
    display_name(&[
        details.details.as_deref(),
        details.product.as_deref(),
        details.name.as_deref(),
        Some("Account"),
    ])
}

fn iban_of(details: Option<&AccountDetails>) -> Option<String> {
    details
        .and_then(|d| d.account_id.as_ref())
        .and_then(|a| first_non_blank(&[a.iban.as_deref()]))
}

/// ISO 4217 `XXX` means "no currency" (Boursorama sends it on cards): treat it as absent.
fn real_currency(currency: Option<&str>) -> Option<String> {
    first_non_blank(&[currency]).filter(|value| !eq_ignore_case(NO_CURRENCY, value))
}

/// Only the exact tokens seen on an ASPSP are mapped; a token we don't know leaves the nature
/// unknown.
fn card_nature_of(token: Option<&str>) -> Option<CardNature> {
    match java_trim(token.unwrap_or("")).to_lowercase().as_str() {
        "immediat_debit" => Some(CardNature::ImmediateDebit),
        "delayed_debit" => Some(CardNature::DeferredDebit),
        _ => None,
    }
}

fn select_balance<'a>(
    uid: &str,
    c: &'a Candidates,
    all: &[(String, Candidates)],
    ownership_complete: bool,
) -> Result<&'a BalanceItem, EbError> {
    if c.selection != Selection::Fallback {
        return preferred(&c.items);
    }
    let label = balance_label(&c.items)?;
    // A shared list can have one generic label, so response shape cannot prove ownership.
    let contested = all.iter().any(|(other, oc)| {
        other != uid
            && oc.selection == Selection::Fallback
            && oc
                .items
                .iter()
                .any(|item| eq_ignore_case(label, item_label(item)))
    });
    if !ownership_complete || contested {
        return Err(EbError::sync(
            "Enable Banking returned ambiguous account balances; cannot establish fallback ownership",
        ));
    }
    preferred(&c.items)
}

fn balance_label(items: &[BalanceItem]) -> Result<&str, EbError> {
    let Some(first) = items.first() else {
        return Err(EbError::sync(
            "Enable Banking returned ambiguous account balances; no unclaimed balance remains",
        ));
    };
    let label = item_label(first);
    if items
        .iter()
        .any(|item| !eq_ignore_case(label, item_label(item)))
    {
        return Err(EbError::sync(
            "Enable Banking returned ambiguous account balances; cannot establish account scope",
        ));
    }
    Ok(label)
}

fn priority(item: &BalanceItem) -> usize {
    item.balance_type
        .as_deref()
        .and_then(|t| BALANCE_TYPE_PRIORITY.iter().position(|p| *p == t))
        .unwrap_or(BALANCE_TYPE_PRIORITY.len())
}

fn preferred(items: &[BalanceItem]) -> Result<&BalanceItem, EbError> {
    let Some(best) = items.iter().map(priority).min() else {
        return Err(EbError::sync("Enable Banking returned no account balances"));
    };
    let mut ranked = items.iter().filter(|item| priority(item) == best);
    let selected = ranked.next().expect("the minimum exists");
    if ranked.next().is_some() {
        return Err(EbError::sync(
            "Enable Banking returned ambiguous account balances; cannot select a balance safely",
        ));
    }
    if selected
        .balance_amount
        .as_ref()
        .and_then(|a| a.amount.as_ref())
        .is_none()
    {
        return Err(EbError::sync(
            "Enable Banking returned an account balance without an amount",
        ));
    }
    Ok(selected)
}

#[cfg(test)]
mod tests {
    use std::collections::HashMap;

    use serde_json::{Value, json};

    use super::*;

    /// details and balances per uid; a missing entry fails that call.
    struct Fake {
        details: HashMap<&'static str, Value>,
        balances: HashMap<&'static str, Value>,
    }

    impl AccountFetch for Fake {
        async fn details(&self, uid: &str) -> Result<Option<AccountDetails>, EbError> {
            match self.details.get(uid) {
                Some(v) => Ok(serde_json::from_value(v.clone()).unwrap()),
                None => Err(EbError::sync(format!("details {uid}"))),
            }
        }

        async fn balances(&self, uid: &str) -> Result<Option<BalancesResponse>, EbError> {
            match self.balances.get(uid) {
                Some(v) => Ok(serde_json::from_value(v.clone()).unwrap()),
                None => Err(EbError::sync(format!("balances {uid}"))),
            }
        }
    }

    fn balance(name: Option<&str>, amount: &str, kind: &str) -> Value {
        json!({"name": name, "balance_amount": {"amount": amount, "currency": "EUR"}, "balance_type": kind})
    }

    async fn run(
        details: Vec<(&'static str, Value)>,
        balances: Vec<(&'static str, Value)>,
        scope: Scope,
    ) -> Result<Vec<AccountData>, EbError> {
        let uids: Vec<String> = details.iter().map(|(u, _)| (*u).to_owned()).collect();
        let fake = Fake {
            details: details.into_iter().collect(),
            balances: balances
                .into_iter()
                .map(|(u, b)| (u, json!({ "balances": b })))
                .collect(),
        };
        resolve(&fake, &uids, scope).await
    }

    fn amounts(result: &[AccountData]) -> Vec<(String, String)> {
        result
            .iter()
            .map(|a| (a.external_id.clone(), a.balance.unwrap().to_string()))
            .collect()
    }

    fn message(result: Result<Vec<AccountData>, EbError>) -> String {
        result.expect_err("a failure").message()
    }

    #[tokio::test]
    async fn an_organization_wide_list_gives_each_account_its_own_balance() {
        let shared = json!([
            balance(Some("Main"), "10.00", "CLBD"),
            balance(Some("Tax"), "20.00", "CLBD")
        ]);
        let result = run(
            vec![
                ("a", json!({"details": "Main"})),
                ("b", json!({"details": "tax"})),
            ],
            vec![("a", shared.clone()), ("b", shared)],
            Scope::Shared,
        )
        .await
        .unwrap();
        assert_eq!(
            amounts(&result),
            [("a".into(), "10.00".into()), ("b".into(), "20.00".into())]
        );
        assert_eq!(result[0].name.as_deref(), Some("Main"));
    }

    #[tokio::test]
    async fn generic_labels_rank_by_iso_balance_type() {
        let result = run(
            vec![("a", json!({"details": "Compte"}))],
            vec![(
                "a",
                json!([
                    balance(Some("x"), "1.00", "ITAV"),
                    balance(Some("x"), "2.00", "CLBD")
                ]),
            )],
            Scope::AccountScoped,
        )
        .await
        .unwrap();
        assert_eq!(amounts(&result), [("a".into(), "2.00".into())]);
    }

    #[tokio::test]
    async fn tied_preferred_types_are_refused_rather_than_picked() {
        let result = run(
            vec![("a", json!({"details": "Compte"}))],
            vec![(
                "a",
                json!([balance(None, "1.00", "CLBD"), balance(None, "2.00", "CLBD")]),
            )],
            Scope::AccountScoped,
        )
        .await;
        assert_eq!(
            message(result),
            "Enable Banking returned ambiguous account balances; cannot select a balance safely"
        );
    }

    #[tokio::test]
    async fn one_failing_account_is_skipped_and_all_failing_rethrows_the_first() {
        let result = run(
            vec![
                ("a", json!({"details": "A"})),
                ("b", json!({"details": "B"})),
            ],
            vec![("b", json!([balance(None, "5", "CLBD")]))],
            Scope::AccountScoped,
        )
        .await
        .unwrap();
        assert_eq!(amounts(&result), [("b".into(), "5".into())]);
        let all = run(
            vec![
                ("a", json!({"details": "A"})),
                ("b", json!({"details": "B"})),
            ],
            vec![],
            Scope::AccountScoped,
        )
        .await;
        assert_eq!(message(all), "balances a");
    }

    #[tokio::test]
    async fn a_malformed_amount_skips_only_that_account() {
        let result = run(
            vec![
                ("a", json!({"details": "A"})),
                ("b", json!({"details": "B"})),
            ],
            vec![
                ("a", json!([balance(None, "12,5", "CLBD")])),
                ("b", json!([balance(None, "-3.10", "CLBD")])),
            ],
            Scope::AccountScoped,
        )
        .await
        .unwrap();
        assert_eq!(amounts(&result), [("b".into(), "-3.10".into())]);
    }

    #[tokio::test]
    async fn the_currency_falls_back_to_the_account_then_eur_and_long_names_are_cut() {
        let long = "N".repeat(130);
        let result = run(
            vec![
                (
                    "a",
                    json!({"details": long, "currency": "USD", "account_id": {"iban": " FR76 "}}),
                ),
                ("b", json!({"product": "Livret"})),
            ],
            vec![
                (
                    "a",
                    json!([{"balance_amount": {"amount": 7}, "balance_type": "CLBD"}]),
                ),
                (
                    "b",
                    json!([{"balance_amount": {"amount": "8"}, "balance_type": "XPCD"}]),
                ),
            ],
            Scope::AccountScoped,
        )
        .await
        .unwrap();
        assert_eq!(result[0].currency.as_deref(), Some("USD"));
        assert_eq!(result[0].name.as_ref().unwrap().len(), 100);
        assert_eq!(result[0].iban.as_deref(), Some("FR76"));
        assert_eq!(result[1].currency.as_deref(), Some("EUR"));
        assert_eq!(result[1].name.as_deref(), Some("Livret"));
    }

    #[tokio::test]
    async fn an_unlabelled_account_in_an_organization_wide_list_takes_only_the_unattributed_entry()
    {
        let shared = json!([
            balance(Some("Operations wallet"), "903.20", "CLBD"),
            balance(Some("Reserve wallet"), "41.75", "XPCD"),
            balance(Some("Petty cash"), "7.50", "ITAV")
        ]);
        let result = run(
            vec![
                ("ops", json!({"details": "Operations wallet"})),
                ("reserve", json!({"details": "Reserve wallet"})),
                ("unlabelled", json!({})),
            ],
            vec![
                ("ops", shared.clone()),
                ("reserve", shared.clone()),
                ("unlabelled", shared),
            ],
            Scope::Unknown,
        )
        .await
        .unwrap();
        assert_eq!(
            amounts(&result),
            [
                ("ops".into(), "903.20".into()),
                ("reserve".into(), "41.75".into()),
                ("unlabelled".into(), "7.50".into())
            ]
        );
    }

    #[tokio::test]
    async fn competing_fallbacks_are_both_refused() {
        let generic = json!([balance(Some("Solde"), "10", "CLBD")]);
        let result = run(
            vec![
                ("a", json!({"details": "A"})),
                ("b", json!({"details": "B"})),
            ],
            vec![("a", generic.clone()), ("b", generic)],
            Scope::Shared,
        )
        .await;
        assert_eq!(
            message(result),
            "Enable Banking returned ambiguous account balances; cannot establish fallback ownership"
        );
    }

    #[tokio::test]
    async fn a_balance_without_amount_fails_instead_of_inventing_zero() {
        let result = run(
            vec![("a", json!({"details": "A"}))],
            vec![("a", json!([{"name": "A", "balance_type": "CLBD"}]))],
            Scope::AccountScoped,
        )
        .await;
        assert_eq!(
            message(result),
            "Enable Banking returned an account balance without an amount"
        );
        let empty = run(
            vec![("a", json!({"details": "A"}))],
            vec![("a", json!([]))],
            Scope::AccountScoped,
        )
        .await;
        assert_eq!(
            message(empty),
            "Enable Banking returned no account balances"
        );
    }

    fn deferred_card() -> Value {
        json!({"name": "Carte Visa Synthetic Holder", "details": "delayed_debit", "cash_account_type": "CARD"})
    }

    async fn only(details: Value, balances: Value, scope: Scope) -> AccountData {
        let mut result = run(vec![("a", details)], vec![("a", balances)], scope)
            .await
            .unwrap();
        assert_eq!(result.len(), 1);
        result.remove(0)
    }

    #[tokio::test]
    async fn a_card_resource_is_a_card_named_after_the_label_the_holder_sees() {
        for (token, nature, name, legacy) in [
            (
                "immediat_debit",
                Some(CardNature::ImmediateDebit),
                "Carte Visa Synthetic Holder",
                Some("immediat_debit"),
            ),
            (
                "DELAYED_DEBIT",
                Some(CardNature::DeferredDebit),
                "Carte Visa Synthetic Holder",
                Some("DELAYED_DEBIT"),
            ),
            ("Visa Premier 0000", None, "Visa Premier 0000", None),
        ] {
            let card = only(
                json!({
                    "account_id": {"iban": null, "other": {"identification": "SYNTHETIC-ID", "scheme_name": "BANK"}},
                    "name": "Carte Visa Synthetic Holder", "details": token, "product": "Carte bancaire",
                    "cash_account_type": "CARD", "usage": "PRIV", "currency": "XXX"
                }),
                json!([
                    balance(Some("Balance"), "-67.41", "OTHR"),
                    balance(Some("Balance temps réel"), "-581.81", "XPCD")
                ]),
                Scope::AccountScoped,
            )
            .await;
            assert_eq!(
                card,
                AccountData {
                    external_id: "a".into(),
                    name: Some(name.into()),
                    iban: None,
                    currency: Some("EUR".into()),
                    balance: Some("-581.81".parse().unwrap()),
                    account_type: AccountType::CreditCard,
                    card_nature: nature,
                    legacy_name: legacy.map(Into::into),
                },
                "{token}"
            );
        }
    }

    #[tokio::test]
    async fn a_card_without_holder_label_falls_back_to_its_product() {
        let card = only(
            json!({"name": " ", "details": "immediat_debit", "product": "Carte bancaire à débit immédiat",
                   "cash_account_type": "card"}),
            json!([balance(Some("Balance"), "0.00", "OTHR")]),
            Scope::AccountScoped,
        )
        .await;
        assert_eq!(card.account_type, AccountType::CreditCard);
        assert_eq!(
            card.name.as_deref(),
            Some("Carte bancaire à débit immédiat")
        );
        assert_eq!(card.legacy_name.as_deref(), Some("immediat_debit"));
        assert_eq!(card.balance, Some(Decimal::ZERO));
    }

    #[tokio::test]
    async fn a_current_account_keeps_its_type_and_name_order() {
        let account = only(
            json!({"account_id": {"iban": "FR00SYNTHETICCURRENT"}, "name": "Synthetic Holder", "details": "",
                   "product": "CAV - SYNTHETIC", "cash_account_type": "CACC", "currency": "EUR"}),
            json!([balance(Some("Balance"), "826.80", "CLBD")]),
            Scope::Unknown,
        )
        .await;
        assert_eq!(account.account_type, AccountType::Checking);
        assert_eq!(account.card_nature, None);
        assert_eq!(account.name.as_deref(), Some("CAV - SYNTHETIC"));
        assert_eq!(account.legacy_name, None);
        assert_eq!(account.iban.as_deref(), Some("FR00SYNTHETICCURRENT"));
    }

    #[tokio::test]
    async fn a_savings_cash_account_is_a_savings_account_whatever_the_case_or_the_balance_list() {
        for (code, scope) in [
            ("SVGS", Scope::AccountScoped),
            (" svgs ", Scope::AccountScoped),
            ("SVGS", Scope::Unknown),
        ] {
            let account = only(
                json!({"name": "", "details": "Synthetic jar", "usage": "PRIV",
                       "cash_account_type": code, "currency": "EUR"}),
                json!([balance(Some("Balance"), "1200.00", "ITAV")]),
                scope,
            )
            .await;
            assert_eq!(account.account_type, AccountType::Savings, "{code:?}");
            assert_eq!(account.name.as_deref(), Some("Synthetic jar"), "{code:?}");
            assert_eq!(
                account.balance,
                Some("1200.00".parse().unwrap()),
                "{code:?}"
            );
        }
    }

    #[tokio::test]
    async fn an_unknown_or_missing_cash_account_type_stays_a_current_account() {
        for details in [
            json!({"name": "Synthetic", "cash_account_type": "TRAS", "currency": "EUR"}),
            json!({"name": "Synthetic", "currency": "EUR"}),
        ] {
            let account = only(
                details,
                json!([balance(Some("Balance"), "10.00", "CLBD")]),
                Scope::AccountScoped,
            )
            .await;
            assert_eq!(account.account_type, AccountType::Checking);
        }
    }

    #[tokio::test]
    async fn the_no_currency_code_of_a_card_is_never_stored() {
        let card = only(
            json!({"name": "Synthetic card", "cash_account_type": "CARD", "currency": "XXX"}),
            json!([{"name": "Balance", "balance_type": "OTHR",
                    "balance_amount": {"currency": "XXX", "amount": "0.00"}}]),
            Scope::AccountScoped,
        )
        .await;
        assert_eq!(card.currency.as_deref(), Some("EUR"));
    }

    #[tokio::test]
    async fn a_card_without_an_amount_owed_stays_a_card_without_balance() {
        for (case, balances) in [
            (
                "credit left only",
                json!([balance(Some("Balance"), "2000.00", "CLAV")]),
            ),
            (
                "a negative available balance",
                json!([balance(Some("Balance"), "-10.00", "ITAV")]),
            ),
            (
                "a positive balance",
                json!([balance(Some("Balance"), "12.50", "OTHR")]),
            ),
            (
                "two owed balances of the same rank",
                json!([
                    balance(Some("Balance"), "-50.00", "OTHR"),
                    balance(Some("Balance"), "-60.00", "OTHR"),
                    balance(Some("Balance"), "2000.00", "CLAV")
                ]),
            ),
        ] {
            let mut details = deferred_card();
            details["currency"] = json!("XXX");
            let card = only(details, balances, Scope::AccountScoped).await;
            assert_eq!(card.account_type, AccountType::CreditCard, "{case}");
            assert_eq!(card.card_nature, Some(CardNature::DeferredDebit), "{case}");
            assert_eq!(card.balance, None, "{case}");
            assert_eq!(card.currency.as_deref(), Some("EUR"), "{case}");
        }
    }

    #[tokio::test]
    async fn a_card_reads_its_amount_owed_never_its_available_credit() {
        let card = only(
            deferred_card(),
            json!([
                balance(Some("Balance"), "-120.00", "OTHR"),
                balance(Some("Available"), "1880.00", "ITAV")
            ]),
            Scope::AccountScoped,
        )
        .await;
        assert_eq!(card.balance, Some("-120.00".parse().unwrap()));
    }

    #[tokio::test]
    async fn a_card_in_a_shared_list_is_imported_as_before() {
        let account = only(
            deferred_card(),
            json!([balance(Some("Balance"), "-50.00", "OTHR")]),
            Scope::Unknown,
        )
        .await;
        assert_eq!(account.account_type, AccountType::Checking);
        assert_eq!(account.name.as_deref(), Some("delayed_debit"));
        let mixed = run(
            vec![("a", deferred_card())],
            vec![(
                "a",
                json!([
                    balance(Some("Balance"), "-50.00", "OTHR"),
                    balance(Some("Synthetic other account"), "900.00", "ITAV")
                ]),
            )],
            Scope::Unknown,
        )
        .await;
        assert!(message(mixed).contains("ambiguous"));
    }

    /// The shape of a Boursorama session, every value synthetic: three current accounts, two
    /// immediate-debit cards that only send a zero OTHR balance, and a deferred-debit card with
    /// OTHR and XPCD. Cards carry no IBAN and declare the currency XXX.
    #[tokio::test]
    async fn a_boursorama_shaped_session_imports_cards_as_cards_and_current_accounts_as_before() {
        fn current(iban: &str) -> Value {
            json!({"account_id": {"iban": iban, "other": null}, "name": "Synthetic Holder Name", "details": "",
                   "usage": "PRIV", "cash_account_type": "CACC", "product": "CAV - BOURSOBANK", "currency": "XXX"})
        }
        fn current_balances(amount: &str) -> Value {
            json!([
                balance(Some("Balance"), amount, "CLBD"),
                balance(Some("Balance temps réél"), amount, "XPCD")
            ])
        }
        fn card(holder: &str, token: &str, product: &str) -> Value {
            json!({"account_id": {"iban": null, "other": {"identification": "SYNTHETIC", "scheme_name": "BANK"}},
                   "name": holder, "details": token, "usage": "PRIV", "cash_account_type": "CARD",
                   "product": product, "currency": "XXX"})
        }
        let immediate = "Carte bancaire à débit immédiat";
        let result = run(
            vec![
                ("cav-1", current("FR00SYNTHETICCAV1")),
                ("cav-2", current("FR00SYNTHETICCAV2")),
                (
                    "card-1",
                    card("Carte Visa 1 Synthetic Holder", "immediat_debit", immediate),
                ),
                ("cav-3", current("FR00SYNTHETICCAV3")),
                (
                    "card-2",
                    card("Carte Visa 2 Synthetic Holder", "immediat_debit", immediate),
                ),
                (
                    "card-3",
                    card(
                        "Carte Visa 3 Synthetic Holder",
                        "delayed_debit",
                        "Carte bancaire à débit différé",
                    ),
                ),
            ],
            vec![
                ("cav-1", current_balances("0.00")),
                ("cav-2", current_balances("1520.34")),
                ("card-1", json!([balance(Some("Balance"), "0.00", "OTHR")])),
                ("cav-3", current_balances("0.00")),
                ("card-2", json!([balance(Some("Balance"), "0.00", "OTHR")])),
                (
                    "card-3",
                    json!([
                        balance(Some("Balance"), "-67.41", "OTHR"),
                        balance(Some("Balance temps réél"), "-581.81", "XPCD")
                    ]),
                ),
            ],
            Scope::of_aspsp(Some("Boursorama Banque")),
        )
        .await
        .unwrap();
        let seen: Vec<_> = result
            .iter()
            .map(|a| {
                (
                    a.external_id.as_str(),
                    a.account_type,
                    a.card_nature,
                    a.name.as_deref().unwrap(),
                    a.legacy_name.as_deref(),
                    a.iban.as_deref(),
                    a.currency.as_deref().unwrap(),
                    a.balance.unwrap().to_string(),
                )
            })
            .collect();
        let checking = AccountType::Checking;
        let card = AccountType::CreditCard;
        let immediate = Some(CardNature::ImmediateDebit);
        assert_eq!(
            seen,
            [
                (
                    "cav-1",
                    checking,
                    None,
                    "CAV - BOURSOBANK",
                    None,
                    Some("FR00SYNTHETICCAV1"),
                    "EUR",
                    "0.00".to_owned()
                ),
                (
                    "cav-2",
                    checking,
                    None,
                    "CAV - BOURSOBANK",
                    None,
                    Some("FR00SYNTHETICCAV2"),
                    "EUR",
                    "1520.34".to_owned()
                ),
                (
                    "card-1",
                    card,
                    immediate,
                    "Carte Visa 1 Synthetic Holder",
                    Some("immediat_debit"),
                    None,
                    "EUR",
                    "0.00".to_owned()
                ),
                (
                    "cav-3",
                    checking,
                    None,
                    "CAV - BOURSOBANK",
                    None,
                    Some("FR00SYNTHETICCAV3"),
                    "EUR",
                    "0.00".to_owned()
                ),
                (
                    "card-2",
                    card,
                    immediate,
                    "Carte Visa 2 Synthetic Holder",
                    Some("immediat_debit"),
                    None,
                    "EUR",
                    "0.00".to_owned()
                ),
                (
                    "card-3",
                    card,
                    Some(CardNature::DeferredDebit),
                    "Carte Visa 3 Synthetic Holder",
                    Some("delayed_debit"),
                    None,
                    "EUR",
                    "-581.81".to_owned()
                ),
            ]
        );
    }
}
