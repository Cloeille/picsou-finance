//! FinaryAccountMatcher: which existing Picsou account a Finary row should map to. Current API
//! external id first, then the legacy XLSX id, then a unique trimmed name of a compatible type;
//! claimed accounts are skipped and the name tier never offers a connector-owned account.

use std::collections::HashSet;

use crate::ledger::model::{Account, AccountType};

pub struct Row<'a> {
    pub finary_id: Option<&'a str>,
    pub name: Option<&'a str>,
    pub category: &'a str,
    pub slug: Option<&'a str>,
}

/// finary_{category}_{id}, a missing part written "null" as Java's concatenation does.
pub fn external_id(category: &str, id: Option<&str>) -> String {
    format!("finary_{category}_{}", id.unwrap_or("null"))
}

/// The XLSX import kept the sheet name in its ids.
fn xlsx_sheet(category: &str) -> &str {
    match category {
        "checkings" => "Checkings",
        "savings" => "Savings",
        "investments" => "Investments",
        "real_estates" => "Real Estate",
        "cryptos" => "Cryptos",
        "fonds_euro" => "Fonds Euro",
        "commodities" => "Commodities",
        "credits" => "Credits",
        "other_assets" => "Other Assets",
        "startups" => "Startups",
        other => other,
    }
}

/// Every row's external-id match is reserved before any row is matched by name, so an earlier
/// same-name row never takes the account a later row is linked to.
pub fn suggest_all<'a>(rows: &[Row<'_>], existing: &'a [Account]) -> Vec<Option<&'a Account>> {
    let mut claimed = HashSet::new();
    let mut out: Vec<Option<&Account>> = rows
        .iter()
        .map(|row| {
            let hit = by_linked_id(row, existing, &claimed);
            if let Some(account) = hit {
                claimed.insert(account.id);
            }
            hit
        })
        .collect();
    for (row, slot) in rows.iter().zip(out.iter_mut()) {
        if slot.is_none() {
            *slot = by_name(row, existing, &claimed);
            if let Some(account) = slot {
                claimed.insert(account.id);
            }
        }
    }
    out
}

/// The first Picsou account two targets share.
pub fn shared_target(targets: &[Option<i64>]) -> Option<i64> {
    let mut seen = HashSet::new();
    targets
        .iter()
        .flatten()
        .copied()
        .find(|id| !seen.insert(*id))
}

/// A synced account another connector finds by its external id: Finary must neither take that
/// id nor replace its holdings.
pub fn owned_by_another_connector(account: &Account) -> bool {
    !account.is_manual && account.external_account_id.is_some()
}

fn by_linked_id<'a>(
    row: &Row<'_>,
    existing: &'a [Account],
    claimed: &HashSet<i64>,
) -> Option<&'a Account> {
    by_external_id(&external_id(row.category, row.finary_id), existing, claimed).or_else(|| {
        let slug = row
            .slug
            .filter(|s| !crate::ledger::sync_values::java_is_blank(s))?;
        by_external_id(
            &external_id(xlsx_sheet(row.category), Some(slug)),
            existing,
            claimed,
        )
    })
}

fn by_external_id<'a>(
    id: &str,
    existing: &'a [Account],
    claimed: &HashSet<i64>,
) -> Option<&'a Account> {
    existing
        .iter()
        .find(|a| !claimed.contains(&a.id) && a.external_account_id.as_deref() == Some(id))
}

fn normalize(name: Option<&str>) -> String {
    name.map(|n| crate::ledger::sync_values::java_trim(n).to_lowercase())
        .unwrap_or_default()
}

fn by_name<'a>(
    row: &Row<'_>,
    existing: &'a [Account],
    claimed: &HashSet<i64>,
) -> Option<&'a Account> {
    let needle = normalize(row.name);
    if needle.is_empty() {
        return None;
    }
    let mut hits = existing.iter().filter(|a| {
        !claimed.contains(&a.id)
            && !owned_by_another_connector(a)
            && normalize(Some(&a.name)) == needle
            && types_compatible(row.category, a.account_type)
    });
    match (hits.next(), hits.next()) {
        (Some(account), None) => Some(account),
        _ => None,
    }
}

fn types_compatible(category: &str, account_type: AccountType) -> bool {
    use AccountType::*;
    match category.to_lowercase().replace(' ', "_").as_str() {
        "checkings" | "checking" => account_type == Checking,
        "savings" | "fonds_euro" | "fonds-euro" => {
            matches!(
                account_type,
                Savings | LivretA | Ldds | Lep | LivretJeune | Pel | Cel
            )
        }
        "investments" => matches!(account_type, CompteTitres | Pea | EmployeeSavings),
        "cryptos" => account_type == Crypto,
        "loans" | "credits" => matches!(account_type, Loan | Other),
        "real_estates" | "real-estate" => account_type == RealEstate,
        _ => account_type == Other,
    }
}

/// suggestTypeFromApiCategory.
pub fn suggest_type(category: &str) -> AccountType {
    match category {
        "checkings" => AccountType::Checking,
        "savings" | "fonds_euro" => AccountType::Savings,
        "investments" => AccountType::CompteTitres,
        "cryptos" => AccountType::Crypto,
        "loans" => AccountType::Loan,
        _ => AccountType::Other,
    }
}

/// defaultColorForType.
pub fn default_color(account_type: AccountType) -> &'static str {
    match account_type {
        AccountType::Pea => "#10b981",
        AccountType::CompteTitres => "#3b82f6",
        AccountType::Crypto => "#f59e0b",
        AccountType::Savings => "#8b5cf6",
        _ => "#6366f1",
    }
}
