//! Accounts, dashboard, history, analysis, logos, securities (track T2).

pub mod connection;
pub mod dto;
pub mod history;
pub mod jdecimal;
pub mod meta;
pub mod ownership;
pub mod query;
pub mod seams;
pub mod service;
pub mod validation;

pub use seams::Seams;

use crate::routes::Routes;

pub fn register(routes: &mut Routes) {
    routes
        .get("/api/accounts", service::find_all)
        .post("/api/accounts", service::create)
        .put("/api/accounts/display-order", service::set_display_order)
        .get("/api/accounts/{id}", service::find_by_id)
        .put("/api/accounts/{id}", service::update)
        .put("/api/accounts/{id}/visibility", service::set_hidden)
        .put("/api/accounts/{id}/display", service::set_collapsed)
        .get("/api/accounts/{id}/history", service::get_history)
        .post("/api/accounts/{id}/history", service::add_snapshot)
        .get("/api/accounts/{id}/transactions", service::get_transactions)
        .post("/api/accounts/{id}/transactions", service::add_transaction)
        .put(
            "/api/accounts/{id}/transactions/{txId}",
            service::update_transaction,
        )
        .delete(
            "/api/accounts/{id}/transactions/{txId}",
            service::delete_transaction,
        )
        .put("/api/accounts/{id}/real-estate", meta::update_real_estate)
        .put("/api/accounts/{id}/debt", meta::update_debt)
        .get("/api/accounts/{id}/loan-summary", meta::loan_summary)
        .put("/api/accounts/{id}/scpi", meta::update_scpi)
        .post(
            "/api/accounts/{id}/valuation/refresh",
            meta::refresh_valuation,
        )
        .get(
            "/api/accounts/{id}/deletion-impact",
            connection::deletion_impact,
        )
        .delete("/api/accounts/{id}", connection::delete_account)
        .get("/api/accounts/{id}/ownership", ownership::get_ownership)
        .put("/api/accounts/{id}/ownership", ownership::replace_ownership)
        .get("/api/history", history::get_history)
        .get("/api/history/pnl", history::get_pnl)
        .get("/api/history/net-worth/intraday", history::get_intraday);
}
