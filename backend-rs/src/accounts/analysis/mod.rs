//! AnalysisController: how the portfolio is built rather than what it is worth (pyramid,
//! diversification, projection, allocation targets, expense estimate, profile refresh).

pub mod diversification;
pub mod expenses;
pub mod inflow;
pub mod java_hash;
pub mod math;
pub mod profiles;
pub mod projection;
pub mod property;
pub mod pyramid;
pub mod seams;
pub mod targets;

use std::collections::HashMap;

use axum::extract::{Query, State};
use axum::http::StatusCode;
use serde::Serialize;

use crate::AppState;
use crate::auth::Auth;
use crate::error::{AppError, AppResult};
use crate::http::client_ip::ClientIp;
use crate::http::rate_limit::policies;
use crate::routes::Routes;

pub fn register(routes: &mut Routes) {
    routes
        .get("/api/analysis/pyramid", pyramid_handler)
        .get("/api/analysis/diversification", diversification_handler)
        .get("/api/analysis/projection", projection_handler)
        .get("/api/analysis/allocation-targets", targets::get)
        .put("/api/analysis/allocation-targets", targets::replace)
        .get(
            "/api/analysis/essential-expenses/estimate",
            estimate_handler,
        )
        .post("/api/analysis/security-profiles/refresh", refresh_handler);
}

async fn pyramid_handler(
    State(state): State<AppState>,
    auth: Auth,
) -> AppResult<axum::Json<pyramid::WealthPyramidResponse>> {
    let mut conn = state.db.pool_handle();
    Ok(axum::Json(
        pyramid::pyramid(
            &mut conn,
            &*state.ledger.prices,
            &*state.accounts.crypto_logos,
            auth.member_id,
            state.clock.today(),
        )
        .await?,
    ))
}

async fn diversification_handler(
    State(state): State<AppState>,
    auth: Auth,
) -> AppResult<axum::Json<diversification::DiversificationResponse>> {
    let mut conn = state.db.pool_handle();
    Ok(axum::Json(
        diversification::diversification(
            &mut conn,
            &*state.ledger.prices,
            &*state.accounts.crypto_logos,
            auth.member_id,
            state.clock.today(),
        )
        .await?,
    ))
}

/// @RequestParam(defaultValue = "20") int years: empty means the default, junk is a 400.
fn years_param(query: &HashMap<String, String>) -> AppResult<i64> {
    match query.get("years").map(String::as_str) {
        None | Some("") => Ok(20),
        Some(raw) => raw.trim().parse::<i32>().map(i64::from).map_err(|_| {
            AppError::BadRequest(format!("Failed to convert 'years' with value: '{raw}'"))
        }),
    }
}

async fn projection_handler(
    State(state): State<AppState>,
    auth: Auth,
    Query(query): Query<HashMap<String, String>>,
) -> AppResult<axum::Json<projection::ProjectionResponse>> {
    let years = years_param(&query)?;
    let today = state.clock.today();
    let mut conn = state.db.pool_handle();
    let pyramid = pyramid::pyramid(
        &mut conn,
        &*state.ledger.prices,
        &*state.accounts.crypto_logos,
        auth.member_id,
        today,
    )
    .await?;
    Ok(axum::Json(
        projection::project(
            &mut conn,
            &*state.ledger.prices,
            &pyramid,
            auth.member_id,
            years,
            today,
        )
        .await?,
    ))
}

async fn estimate_handler(
    State(state): State<AppState>,
    auth: Auth,
) -> AppResult<axum::Json<expenses::EssentialExpenseEstimateResponse>> {
    let mut conn = state.db.pool_handle();
    Ok(axum::Json(
        expenses::estimate(
            &mut conn,
            &*state.ledger.prices,
            auth.member_id,
            state.clock.today(),
        )
        .await?,
    ))
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct SecurityProfileRefreshResponse {
    queued_tickers: usize,
    already_running: bool,
}

/// 202, never 200: the pass outlives the request. On the sync bucket, as Java.
async fn refresh_handler(
    State(state): State<AppState>,
    _auth: Auth,
    ip: ClientIp,
) -> AppResult<(StatusCode, axum::Json<SecurityProfileRefreshResponse>)> {
    if !state
        .limits
        .try_consume(&policies::SYNC, &ip.as_java_string())
    {
        return Err(AppError::status(
            StatusCode::TOO_MANY_REQUESTS,
            "Too many refresh requests. Please wait a moment.",
        ));
    }
    let body = match state
        .accounts
        .analysis
        .profile_runner
        .trigger(&state.db)
        .await?
    {
        profiles::Trigger::Queued(n) => SecurityProfileRefreshResponse {
            queued_tickers: n,
            already_running: false,
        },
        profiles::Trigger::AlreadyRunning => SecurityProfileRefreshResponse {
            queued_tickers: 0,
            already_running: true,
        },
    };
    Ok((StatusCode::ACCEPTED, axum::Json(body)))
}
