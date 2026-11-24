//! FinaryAccountMatcher as the file import calls it: rows keyed by the slug of their name under
//! their sheet's name, matched by the external id the import writes, then by a unique name of a
//! compatible type. The legacy-slug tier only serves the API sync (track T5).

use std::collections::HashSet;

use crate::ledger::model::{Account, AccountType};
use crate::ledger::sync_values::java_trim;

pub struct Row<'a> {
    /// slugify(name): the id part of finary_{sheet}_{slug}.
    pub finary_id: String,
    pub name: &'a str,
    /// The sheet name (Checkings, Real Estate, ...).
    pub category: &'a str,
}

/// suggestAll: every row's external-id match is reserved before any row is matched by name, so
/// an earlier row with the same name never takes an account a later row is linked to.
pub fn suggest_all(rows: &[Row<'_>], existing: &[Account]) -> Vec<Option<i64>> {
    let mut claimed = HashSet::new();
    let mut out: Vec<Option<i64>> = rows
        .iter()
        .map(|row| {
            let external_id = format!("finary_{}_{}", row.category, row.finary_id);
            let hit = existing
                .iter()
                .find(|a| {
                    !claimed.contains(&a.id)
                        && a.external_account_id.as_deref() == Some(&external_id)
                })
                .map(|a| a.id);
            if let Some(id) = hit {
                claimed.insert(id);
            }
            hit
        })
        .collect();
    for (row, slot) in rows.iter().zip(out.iter_mut()) {
        if slot.is_some() {
            continue;
        }
        let needle = normalize(row.name);
        if needle.is_empty() {
            continue;
        }
        let hits: Vec<i64> = existing
            .iter()
            .filter(|a| !claimed.contains(&a.id))
            .filter(|a| !owned_by_another_connector(a))
            .filter(|a| normalize(&a.name) == needle)
            .filter(|a| types_compatible(row.category, a.account_type))
            .map(|a| a.id)
            .collect();
        if let [id] = hits[..] {
            claimed.insert(id);
            *slot = Some(id);
        }
    }
    out
}

/// sharedTarget: the first account two of these targets share.
pub fn shared_target(targets: impl IntoIterator<Item = Option<i64>>) -> Option<i64> {
    let mut seen = HashSet::new();
    targets.into_iter().flatten().find(|id| !seen.insert(*id))
}

/// A synced account another connector finds by its external id; Finary accounts are manual.
fn owned_by_another_connector(account: &Account) -> bool {
    !account.is_manual && account.external_account_id.is_some()
}

fn normalize(name: &str) -> String {
    java_trim(name).to_lowercase()
}

/// typesCompatible for the sheet names (lower-cased, spaces as underscores).
fn types_compatible(category: &str, kind: AccountType) -> bool {
    use AccountType::*;
    match category.to_lowercase().replace(' ', "_").as_str() {
        "checkings" => kind == Checking,
        "savings" | "fonds_euro" => matches!(
            kind,
            Savings | LivretA | Ldds | Lep | LivretJeune | Pel | Cel
        ),
        "investments" => matches!(kind, CompteTitres | Pea | EmployeeSavings),
        "cryptos" => kind == Crypto,
        "credits" => matches!(kind, Loan | Other),
        // "real_estate" is not one of Java's spellings ("real_estates", "real-estate"): OTHER.
        _ => kind == Other,
    }
}
