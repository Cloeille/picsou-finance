//! Integration tests of the wealth module.

mod dividends;
mod goals;
mod peer_loans;
mod real_estate;
mod savings;
mod scpi;
mod scpi_reconcile;
mod support;

#[test]
fn all_eight_t4_controllers_are_fully_covered() {
    use regex::Regex;
    use std::collections::BTreeSet;

    const JAVA_ENDPOINTS: &str = include_str!("../../scripts/java_endpoints.tsv");
    let var = Regex::new(r"\{[^}]*\}").unwrap();
    let normalize = |p: &str| var.replace_all(p, "{}").into_owned();
    let routes = picsou::routes::all();
    let rust: BTreeSet<(String, String)> = routes
        .list()
        .iter()
        .map(|(m, p)| (m.to_string(), normalize(p)))
        .collect();
    let controllers = [
        "GoalController",
        "SavingsController",
        "PeerLoanController",
        "RealEstateController",
        "GeocodingController",
        "ScpiFundController",
        "DividendController",
        "PriceController",
    ];
    let t4: Vec<_> = JAVA_ENDPOINTS
        .lines()
        .filter(|l| !l.trim().is_empty())
        .filter_map(|l| {
            let cols: Vec<&str> = l.split('\t').collect();
            let controller = cols.get(2).copied()?;
            controllers.contains(&controller).then(|| {
                (
                    cols[0].to_owned(),
                    normalize(cols[1]),
                    controller.to_owned(),
                )
            })
        })
        .collect();
    assert_eq!(t4.len(), 40);
    for (method, path, controller) in t4 {
        assert!(
            rust.contains(&(method.clone(), path.clone())),
            "{controller}: {method} {path} not served"
        );
    }
}
