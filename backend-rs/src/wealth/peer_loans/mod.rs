//! Loans between people (PeerLoanController, PeerLoanService): money lent to a person,
//! tracked as a receivable on a PEER_LOAN account. Only facts are stored (terms and dated
//! repayments); remaining capital, interest and the plan are recomputed on every read by
//! crate::ledger::peer_loans.

mod dto;
mod repo;
mod service;

use axum::extract::State;
use axum::http::StatusCode;
use axum::response::IntoResponse;

pub use dto::*;

use crate::AppState;
use crate::auth::Auth;
use crate::error::AppResult;
use crate::http::json::{Json, Path};
use crate::routes::Routes;
use crate::wealth::params::{self, Query};

pub fn register(routes: &mut Routes) {
    routes
        .get("/api/accounts/{id}/peer-loan", get)
        .put("/api/accounts/{id}/peer-loan", save_terms)
        .post("/api/accounts/{id}/peer-loan/repayments", add_repayment)
        .post(
            "/api/accounts/{id}/peer-loan/repayments/from-transaction",
            add_repayment_from_transaction,
        )
        .put(
            "/api/accounts/{id}/peer-loan/repayments/{repaymentId}",
            update_repayment,
        )
        .delete(
            "/api/accounts/{id}/peer-loan/repayments/{repaymentId}",
            delete_repayment,
        )
        .post("/api/accounts/{id}/peer-loan/close", close)
        .post("/api/peer-loans/simulate", simulate)
        .get("/api/peer-loans/open", open_loans)
        .get("/api/peer-loans/overview", overview)
        .post("/api/peer-loans/from-transaction", create_from_transaction);
}

async fn get(
    State(state): State<AppState>,
    auth: Auth,
    Path(id): Path<i64>,
) -> AppResult<axum::Json<PeerLoanDetailResponse>> {
    service::get(&state, id, auth.member_id).await.map(axum::Json)
}

async fn save_terms(
    State(state): State<AppState>,
    auth: Auth,
    Path(id): Path<i64>,
    Json(req): Json<PeerLoanTermsRequest>,
) -> AppResult<axum::Json<PeerLoanDetailResponse>> {
    service::save_terms(&state, id, auth.member_id, &req)
        .await
        .map(axum::Json)
}

async fn add_repayment(
    State(state): State<AppState>,
    auth: Auth,
    Path(id): Path<i64>,
    Json(req): Json<PeerLoanRepaymentRequest>,
) -> AppResult<impl IntoResponse> {
    let created = service::add_repayment(&state, id, auth.member_id, &req).await?;
    Ok((StatusCode::CREATED, axum::Json(created)))
}

async fn add_repayment_from_transaction(
    State(state): State<AppState>,
    auth: Auth,
    Path(id): Path<i64>,
    Json(req): Json<PeerLoanRepaymentFromTransactionRequest>,
) -> AppResult<axum::Json<PeerLoanDetailResponse>> {
    service::add_repayment_from_transaction(&state, id, auth.member_id, &req)
        .await
        .map(axum::Json)
}

async fn update_repayment(
    State(state): State<AppState>,
    auth: Auth,
    Path((id, repayment_id)): Path<(i64, i64)>,
    Json(req): Json<PeerLoanRepaymentRequest>,
) -> AppResult<axum::Json<PeerLoanRepaymentResponse>> {
    service::update_repayment(&state, id, repayment_id, auth.member_id, &req)
        .await
        .map(axum::Json)
}

async fn delete_repayment(
    State(state): State<AppState>,
    auth: Auth,
    Path((id, repayment_id)): Path<(i64, i64)>,
) -> AppResult<StatusCode> {
    service::delete_repayment(&state, id, repayment_id, auth.member_id).await?;
    Ok(StatusCode::NO_CONTENT)
}

async fn close(
    State(state): State<AppState>,
    auth: Auth,
    Path(id): Path<i64>,
    query: Query,
) -> AppResult<axum::Json<PeerLoanDetailResponse>> {
    let date = params::optional(&query, "date")?;
    service::close(&state, id, auth.member_id, date)
        .await
        .map(axum::Json)
}

async fn simulate(
    _auth: Auth,
    Json(req): Json<PeerLoanSimulationRequest>,
) -> AppResult<axum::Json<PeerLoanSimulationResponse>> {
    service::simulate(&req).map(axum::Json)
}

async fn open_loans(
    State(state): State<AppState>,
    auth: Auth,
) -> AppResult<axum::Json<Vec<PeerLoanOpenResponse>>> {
    service::open_loans(&state, auth.member_id)
        .await
        .map(axum::Json)
}

async fn overview(
    State(state): State<AppState>,
    auth: Auth,
    query: Query,
) -> AppResult<axum::Json<PeerLoansOverviewResponse>> {
    let months = params::or_default(&query, "months", 12)?;
    service::overview(&state, auth.member_id, months)
        .await
        .map(axum::Json)
}

async fn create_from_transaction(
    State(state): State<AppState>,
    auth: Auth,
    Json(req): Json<PeerLoanFromTransactionRequest>,
) -> AppResult<impl IntoResponse> {
    let created = service::create_from_transaction(&state, auth.member_id, &req).await?;
    Ok((StatusCode::CREATED, axum::Json(created)))
}
