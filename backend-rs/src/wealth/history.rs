//! The net-worth history the goal and peer-loan endpoints read: HistoryService.buildHistory,
//! owned by track T2. Until the coordinator plugs T2's in, those endpoints answer 501.

use std::collections::BTreeMap;

use async_trait::async_trait;
use chrono::NaiveDate;
use rust_decimal::Decimal;
use serde::Serialize;

use crate::AppState;
use crate::error::{AppError, AppResult};

/// DashboardResponse.AccountPoint.
#[serde_with::skip_serializing_none]
#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AccountPoint {
    #[serde(with = "rust_decimal::serde::arbitrary_precision_option")]
    pub total: Option<Decimal>,
    #[serde(with = "rust_decimal::serde::arbitrary_precision_option")]
    pub invested: Option<Decimal>,
    #[serde(with = "rust_decimal::serde::arbitrary_precision_option")]
    pub pnl: Option<Decimal>,
}

/// DashboardResponse.NetWorthPoint; accounts is set only by a split history.
#[serde_with::skip_serializing_none]
#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct NetWorthPoint {
    pub date: NaiveDate,
    #[serde(with = "rust_decimal::serde::arbitrary_precision_option")]
    pub total: Option<Decimal>,
    #[serde(with = "rust_decimal::serde::arbitrary_precision_option")]
    pub invested: Option<Decimal>,
    #[serde(with = "rust_decimal::serde::arbitrary_precision_option")]
    pub pnl: Option<Decimal>,
    pub accounts: Option<BTreeMap<i64, AccountPoint>>,
}

#[async_trait]
pub trait HistorySource: Send + Sync {
    /// buildHistory(accountIds, months, split, memberId): daily points over the accounts.
    async fn build_history(
        &self,
        state: &AppState,
        account_ids: &[i64],
        months: i32,
        split: bool,
        member_id: i64,
    ) -> AppResult<Vec<NetWorthPoint>>;
}

/// No history source plugged in yet.
pub struct Unwired;

#[async_trait]
impl HistorySource for Unwired {
    async fn build_history(
        &self,
        _state: &AppState,
        _account_ids: &[i64],
        _months: i32,
        _split: bool,
        _member_id: i64,
    ) -> AppResult<Vec<NetWorthPoint>> {
        Err(AppError::NotImplemented)
    }
}
