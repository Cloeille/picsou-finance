//! /actuator/health with its liveness and readiness groups, and /actuator/info, as Spring Boot
//! Actuator renders them (show-details never).

use axum::extract::State;
use axum::http::{HeaderMap, HeaderValue, StatusCode, header};
use axum::response::{IntoResponse, Response};
use serde_json::{Map, Value, json};

use crate::AppState;
use crate::routes::Routes;

const ACTUATOR_V3: &str = "application/vnd.spring-boot.actuator.v3+json";

fn actuator_json(headers: &HeaderMap, status: StatusCode, body: Value) -> Response {
    let accept = headers
        .get(header::ACCEPT)
        .and_then(|v| v.to_str().ok())
        .unwrap_or("*/*");
    let content_type = if accept.contains(ACTUATOR_V3) || !accept.contains("application/json") {
        ACTUATOR_V3
    } else {
        "application/json"
    };
    let mut response = (status, body.to_string()).into_response();
    response
        .headers_mut()
        .insert(header::CONTENT_TYPE, HeaderValue::from_static(content_type));
    response
}

async fn health(State(state): State<AppState>, headers: HeaderMap) -> Response {
    let up = sqlx::query("SELECT 1").execute(&state.db).await.is_ok();
    let (status, label) = if up {
        (StatusCode::OK, "UP")
    } else {
        (StatusCode::SERVICE_UNAVAILABLE, "DOWN")
    };
    actuator_json(
        &headers,
        status,
        json!({"status": label, "groups": ["liveness", "readiness"]}),
    )
}

/// Once started, neither probe looks at the database: an outage must not restart the pod.
async fn probe(headers: HeaderMap) -> Response {
    actuator_json(&headers, StatusCode::OK, json!({"status": "UP"}))
}

async fn info(State(state): State<AppState>, headers: HeaderMap) -> Response {
    let mut app = Map::new();
    app.insert("name".into(), Value::from("picsou"));
    app.insert(
        "version".into(),
        Value::from(state.config.app_version.clone()),
    );
    // Always present, as strings, like Spring's info.app.* properties: the SPA marks the banks the
    // desktop bundle does not ship as unavailable when desktop is "true".
    app.insert("desktop".into(), Value::from(state.config.desktop.clone()));
    app.insert(
        "desktop-unavailable".into(),
        Value::from(state.config.desktop_unavailable.clone()),
    );
    actuator_json(&headers, StatusCode::OK, json!({ "app": app }))
}

pub fn register(routes: &mut Routes) {
    for path in [
        "/actuator/health",
        "/actuator/health/liveness",
        "/actuator/health/readiness",
        "/actuator/info",
    ] {
        routes.permit_all(None, path);
    }
    routes
        .get("/actuator/health", health)
        .get("/actuator/health/liveness", probe)
        .get("/actuator/health/readiness", probe)
        .get("/actuator/info", info);
}
