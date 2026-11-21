//! Integration tests of the identity module.

mod access_keys;
mod fake_idp;
mod oauth;
mod profile;
mod sign_in;
mod sso;

use std::collections::BTreeSet;

const JAVA_ENDPOINTS: &str = include_str!("../../scripts/java_endpoints.tsv");
const T1_CONTROLLERS: &[&str] = &[
    "SignInMethodsController",
    "AccessKeyController",
    "ConnectedAppsController",
    "OAuthConsentController",
    "MeProfileController",
    "AdminMfaController",
    "AdminIdentityController",
    "AdminController",
    "TelemetryController",
    "DynamicClientRegistrationController",
    "ProtectedResourceMetadataController",
];

#[test]
fn every_t1_endpoint_is_served() {
    let normalize = |p: &str| {
        regex::Regex::new(r"\{[^}]*\}")
            .unwrap()
            .replace_all(p, "{}")
            .into_owned()
    };
    let served: BTreeSet<(String, String)> = picsou::routes::all()
        .list()
        .iter()
        .map(|(m, p)| (m.to_string(), normalize(p)))
        .collect();
    let mut count = 0;
    for line in JAVA_ENDPOINTS.lines().filter(|l| !l.trim().is_empty()) {
        let cols: Vec<&str> = line.split('\t').collect();
        if !T1_CONTROLLERS.contains(&cols[2]) {
            continue;
        }
        count += 1;
        assert!(
            served.contains(&(cols[0].to_owned(), normalize(cols[1]))),
            "{}: {} {} not served",
            cols[2],
            cols[0],
            cols[1]
        );
    }
    assert_eq!(count, 34);
}
