//! fetchBalances without the HTTP: which balance of a session belongs to which account. Some ASPSPs
//! return every sibling's balances under each account uid, so a balance is only taken when the
//! session's account descriptions prove whose it is.

use std::collections::HashMap;

use rust_decimal::Decimal;
use serde::Deserialize;

use super::error::EbError;
use super::wire::{first_non_blank, lenient_string, parse_big_decimal, truncate_utf16};
use crate::error::AppError;
use crate::ledger::sync_values::java_trim;

const BALANCE_TYPE_PRIORITY: [&str; 5] = ["CLBD", "XPCD", "ITBD", "ITAV", "CLAV"];
/// account.name is VARCHAR(100).
const MAX_ACCOUNT_NAME_LENGTH: usize = 100;

#[derive(Debug, Clone, Default, Deserialize)]
pub struct AccountDetails {
    pub account_id: Option<AccountIdentification>,
    pub name: Option<String>,
    pub details: Option<String>,
    pub product: Option<String>,
    pub currency: Option<String>,
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
    pub balance: Decimal,
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
    let selected = select_balance(uid, c, all, ownership_complete)?;
    let amount = selected
        .balance_amount
        .as_ref()
        .and_then(|a| a.amount.as_deref())
        .unwrap_or_default();
    let balance = parse_big_decimal(java_trim(amount)).ok_or_else(|| {
        EbError::Other(AppError::Internal(format!(
            "NumberFormatException: malformed Enable Banking balance amount for account {}",
            super::wire::fingerprint(Some(uid))
        )))
    })?;
    let details = c.details.as_ref();
    let currency = first_non_blank(&[
        selected
            .balance_amount
            .as_ref()
            .and_then(|a| a.currency.as_deref()),
        details.and_then(|d| d.currency.as_deref()),
        Some("EUR"),
    ]);
    let name = match details {
        Some(d) => first_non_blank(&[
            d.details.as_deref(),
            d.product.as_deref(),
            d.name.as_deref(),
            Some("Account"),
        ]),
        None => Some("Account".to_owned()),
    };
    let iban = details
        .and_then(|d| d.account_id.as_ref())
        .and_then(|a| first_non_blank(&[a.iban.as_deref()]));
    Ok(AccountData {
        external_id: uid.to_owned(),
        name: name.map(|n| truncate_utf16(&n, MAX_ACCOUNT_NAME_LENGTH)),
        iban,
        currency,
        balance,
    })
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
            .map(|a| (a.external_id.clone(), a.balance.to_string()))
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
}
