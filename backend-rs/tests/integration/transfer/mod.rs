//! Integration tests of the transfer module.

use std::collections::BTreeSet;

use regex::Regex;

mod account_export;
mod actual;
mod csv_import;
mod finary;
mod homebank;
mod me_export;
pub mod support;

const JAVA_ENDPOINTS: &str = include_str!("../../../scripts/java_endpoints.tsv");

#[test]
fn the_transfer_controllers_are_fully_covered() {
    let var = Regex::new(r"\{[^}]*\}").unwrap();
    let normalize = |path: &str| var.replace_all(path, "{}").into_owned();
    let rust: BTreeSet<(String, String)> = picsou::routes::all()
        .list()
        .iter()
        .map(|(m, p)| (m.to_string(), normalize(p)))
        .collect();
    let transfer: Vec<(String, String, String)> = JAVA_ENDPOINTS
        .lines()
        .filter(|l| !l.trim().is_empty())
        .map(|l| {
            let cols: Vec<&str> = l.split('\t').collect();
            (
                cols[0].to_owned(),
                normalize(cols[1]),
                cols.get(2).copied().unwrap_or_default().to_owned(),
            )
        })
        .filter(|(_, _, c)| {
            [
                "TransactionImportController",
                "ActualBudgetImportController",
                "HomeBankImportController",
                "FinaryImportController",
                "AccountExportController",
                "MeExportController",
            ]
            .contains(&c.as_str())
        })
        .collect();
    assert_eq!(transfer.len(), 11);
    for (method, path, controller) in transfer {
        assert!(
            rust.contains(&(method.clone(), path.clone())),
            "{controller}: {method} {path} not served"
        );
    }
}
