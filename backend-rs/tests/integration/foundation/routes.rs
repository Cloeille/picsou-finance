use std::collections::BTreeSet;

use regex::Regex;

const JAVA_ENDPOINTS: &str = include_str!("../../../scripts/java_endpoints.tsv");

/// Endpoints served by the framework, not by a controller: Spring Boot Actuator, the Spring
/// Authorization Server endpoints and the OAuth2 login filter chain of SSO.
const FRAMEWORK_ROUTES: &[(&str, &str)] = &[
    ("GET", "/actuator/health"),
    ("GET", "/actuator/health/liveness"),
    ("GET", "/actuator/health/readiness"),
    ("GET", "/actuator/info"),
    ("GET", "/.well-known/oauth-authorization-server"),
    ("GET", "/oauth2/authorize"),
    ("POST", "/oauth2/authorize"),
    ("POST", "/oauth2/token"),
    ("POST", "/oauth2/revoke"),
    ("POST", "/oauth2/introspect"),
    ("POST", "/oauth2/device_authorization"),
    ("GET", "/oauth2/device_verification"),
    ("GET", "/oauth2/jwks"),
    ("GET", "/oauth2/sso/{}"),
    ("POST", "/oauth2/sso/{}"),
    ("GET", "/mcp"),
    ("POST", "/mcp"),
    ("DELETE", "/mcp"),
    ("POST", "/mcp/message"),
    // Desktop-only local endpoints (return 404 unless APP_DESKTOP_MODE=true; loopback-only).
    ("POST", "/api/setup/desktop-quick-start"),
    ("POST", "/api/auth/desktop-autologin"),
    ("GET", "/api/desktop/lock"),
    ("PUT", "/api/desktop/lock"),
    // Desktop-only interactive WebView bank connector endpoints (additive, not in Java).
    ("POST", "/api/bnp/webview/start"),
    ("GET", "/api/bnp/webview/status/{}"),
    ("POST", "/api/bnp/webview/cancel/{}"),
    ("POST", "/api/amex/webview/start"),
    ("GET", "/api/amex/webview/status/{}"),
    ("POST", "/api/amex/webview/cancel/{}"),
    ("POST", "/api/revolut/webview/start"),
    ("GET", "/api/revolut/webview/status/{}"),
    ("POST", "/api/revolut/webview/cancel/{}"),
];

/// Spring and axum share the {name} syntax; a regex constraint ({id:\d+}) and the variable name
/// are not part of the route's identity.
fn normalize(path: &str) -> String {
    let var = Regex::new(r"\{[^}]*\}").unwrap();
    var.replace_all(path, "{}").into_owned()
}

fn java_routes() -> BTreeSet<(String, String, String)> {
    JAVA_ENDPOINTS
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
        .collect()
}

#[test]
fn every_rust_route_is_a_java_endpoint() {
    let java = java_routes();
    let java_keys: BTreeSet<(String, String)> = java
        .iter()
        .map(|(m, p, _)| (m.clone(), p.clone()))
        .collect();
    let routes = picsou::routes::all();
    let rust: BTreeSet<(String, String)> = routes
        .list()
        .iter()
        .map(|(m, p)| (m.to_string(), normalize(p)))
        .collect();

    let unknown: Vec<_> = rust
        .iter()
        .filter(|r| {
            !java_keys.contains(*r) && !FRAMEWORK_ROUTES.contains(&(r.0.as_str(), r.1.as_str()))
        })
        .collect();
    assert!(
        unknown.is_empty(),
        "Rust routes with no Java endpoint: {unknown:?}"
    );

    let missing: Vec<_> = java_keys.iter().filter(|j| !rust.contains(*j)).collect();
    println!(
        "route coverage: {} of {} Java endpoints served",
        java_keys.len() - missing.len(),
        java_keys.len()
    );
    if std::env::var("PICSOU_REQUIRE_FULL_COVERAGE").is_ok_and(|v| v == "1") {
        assert!(missing.is_empty(), "Java endpoints not served: {missing:?}");
    }
}

#[test]
fn the_foundation_controllers_are_fully_covered() {
    let routes = picsou::routes::all();
    let rust: BTreeSet<(String, String)> = routes
        .list()
        .iter()
        .map(|(m, p)| (m.to_string(), normalize(p)))
        .collect();
    let foundation: Vec<_> = java_routes()
        .into_iter()
        .filter(|(_, _, c)| {
            [
                "SetupController",
                "AuthController",
                "MfaController",
                "SessionController",
            ]
            .contains(&c.as_str())
        })
        .collect();
    assert_eq!(foundation.len(), 26);
    for (method, path, controller) in foundation {
        assert!(
            rust.contains(&(method.clone(), path.clone())),
            "{controller}: {method} {path} not served"
        );
    }
}
