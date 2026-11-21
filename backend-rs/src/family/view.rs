//! FamilyViewController: the family dashboard (what the other members share) and the
//! per-member breakdown of a goal's manual contributions.

use std::collections::{BTreeSet, HashMap};

use axum::extract::State;
use rust_decimal::Decimal;
use serde::Serialize;
use sqlx::{FromRow, PgConnection};

use super::sharing::{self, ACCOUNT, GOAL, SharingLevel};
use crate::AppState;
use crate::auth::Auth;
use crate::error::{AppError, AppResult};
use crate::http::json::Path;
use crate::ledger::model::{Account, FamilyMember};
use crate::ledger::{access, accounts, members, valuation};
use crate::routes::Routes;

pub fn register(routes: &mut Routes) {
    routes
        .get("/api/family/dashboard", dashboard)
        .get("/api/family/goals/{goalId}/contributions", contributions);
}

#[serde_with::skip_serializing_none]
#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct FamilyDashboardResponse {
    shared_accounts: Vec<SharedAccountInfo>,
    shared_goals: Vec<SharedGoalInfo>,
    #[serde(with = "crate::accounts::jdecimal")]
    total_shared_net_worth: Decimal,
}

#[serde_with::skip_serializing_none]
#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SharedAccountInfo {
    id: i64,
    owner_name: String,
    name: String,
    #[serde(rename = "type")]
    account_type: String,
    currency: String,
    #[serde(with = "crate::accounts::jdecimal")]
    balance: Decimal,
    #[serde(with = "crate::accounts::jdecimal")]
    balance_eur: Decimal,
}

#[serde_with::skip_serializing_none]
#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SharedGoalInfo {
    id: i64,
    owner_name: String,
    name: String,
    #[serde(with = "crate::accounts::jdecimal::option")]
    target_amount: Option<Decimal>,
    #[serde(with = "crate::accounts::jdecimal")]
    current_total: Decimal,
    contributions: Vec<ContributionInfo>,
}

/// ContributionInfo and ContributionBreakdownResponse: the same two fields.
#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ContributionInfo {
    member_name: String,
    #[serde(with = "crate::accounts::jdecimal")]
    amount: Decimal,
}

#[derive(FromRow)]
struct GoalRow {
    id: i64,
    member_id: i64,
    name: String,
    target_amount: Option<Decimal>,
}

const GOAL_COLUMNS: &str = "id, member_id, name, target_amount";

/// The accounts the member shares: every visible one for ALL, the stored ids they still own
/// for MANUAL (a stale or foreign id is skipped, never leaked).
async fn shared_accounts(conn: &mut PgConnection, member_id: i64) -> sqlx::Result<Vec<Account>> {
    match sharing::level(&mut *conn, member_id, ACCOUNT).await? {
        SharingLevel::None => Ok(Vec::new()),
        SharingLevel::All => accounts::find_visible_by_member(&mut *conn, member_id).await,
        SharingLevel::Manual => {
            let ids = sharing::shared_ids(&mut *conn, member_id, ACCOUNT).await?;
            accounts::find_by_ids_and_member(&mut *conn, &ids, member_id).await
        }
    }
}

async fn shared_goals(conn: &mut PgConnection, member_id: i64) -> sqlx::Result<Vec<GoalRow>> {
    match sharing::level(&mut *conn, member_id, GOAL).await? {
        SharingLevel::None => Ok(Vec::new()),
        SharingLevel::All => {
            sqlx::query_as(&format!(
                "SELECT {GOAL_COLUMNS} FROM goal WHERE member_id = $1 ORDER BY created_at, id"
            ))
            .bind(member_id)
            .fetch_all(&mut *conn)
            .await
        }
        SharingLevel::Manual => {
            let ids = sharing::shared_ids(&mut *conn, member_id, GOAL).await?;
            sqlx::query_as(&format!(
                "SELECT {GOAL_COLUMNS} FROM goal WHERE id = ANY($1) AND member_id = $2 ORDER BY id"
            ))
            .bind(&ids)
            .bind(member_id)
            .fetch_all(&mut *conn)
            .await
        }
    }
}

/// Each goal's live (not soft-deleted) linked accounts, keyed by goal id.
async fn goal_accounts(
    conn: &mut PgConnection,
    goal_ids: &[i64],
) -> sqlx::Result<HashMap<i64, Vec<Account>>> {
    let links: Vec<(i64, i64)> = sqlx::query_as(
        "SELECT goal_id, account_id FROM goal_account WHERE goal_id = ANY($1) ORDER BY goal_id, account_id",
    )
    .bind(goal_ids)
    .fetch_all(&mut *conn)
    .await?;
    let ids: Vec<i64> = links
        .iter()
        .map(|(_, a)| *a)
        .collect::<BTreeSet<_>>()
        .into_iter()
        .collect();
    let by_id: HashMap<i64, Account> = accounts::find_by_ids(&mut *conn, &ids)
        .await?
        .into_iter()
        .map(|a| (a.id, a))
        .collect();
    let mut out: HashMap<i64, Vec<Account>> = HashMap::new();
    for (goal_id, account_id) in links {
        if let Some(account) = by_id.get(&account_id) {
            out.entry(goal_id).or_default().push(account.clone());
        }
    }
    Ok(out)
}

/// Every member's positive total of manual contributions to the goal, in member order.
async fn contributions_of(
    conn: &mut PgConnection,
    goal_id: i64,
    all_members: &[FamilyMember],
) -> sqlx::Result<Vec<ContributionInfo>> {
    let totals: HashMap<i64, Decimal> = sqlx::query_as::<_, (i64, Decimal)>(
        "SELECT member_id, SUM(amount) FROM goal_manual_contribution \
         WHERE goal_id = $1 AND member_id IS NOT NULL GROUP BY member_id",
    )
    .bind(goal_id)
    .fetch_all(&mut *conn)
    .await?
    .into_iter()
    .collect();
    Ok(all_members
        .iter()
        .filter_map(|m| {
            let total = *totals.get(&m.id)?;
            (total > Decimal::ZERO).then(|| ContributionInfo {
                member_name: m.display_name.clone(),
                amount: total,
            })
        })
        .collect())
}

pub async fn dashboard(
    State(state): State<AppState>,
    auth: Auth,
) -> AppResult<axum::Json<FamilyDashboardResponse>> {
    let today = state.clock.today();
    let prices = &*state.ledger.prices;
    let mut conn = state.db.acquire().await?;
    let all_members = members::find_all(&mut *conn).await?;
    let mut shared_account_infos = Vec::new();
    let mut shared_goal_infos = Vec::new();
    let mut total = Decimal::ZERO;

    for member in all_members.iter().filter(|m| m.id != auth.member_id) {
        let accounts = shared_accounts(&mut conn, member.id).await?;
        // Weighted by the owner's share: the viewer's own half of a co-owned house already
        // shows on their personal dashboard.
        let shares = access::shares_for(&mut *conn, &accounts, member.id).await?;
        let listed: BTreeSet<i64> = accounts.iter().map(|a| a.id).collect();
        for account in &accounts {
            let signed =
                valuation::signed_live_balance_eur(&mut conn, prices, account, today).await?;
            let balance_eur =
                access::weigh(signed, shares.get(&account.id).copied().unwrap_or_default());
            // A pocket is inside its parent wallet's balance; it counts only when the parent
            // is not listed to carry it.
            if account
                .parent_account_id
                .is_none_or(|p| !listed.contains(&p))
            {
                total += balance_eur;
            }
            shared_account_infos.push(SharedAccountInfo {
                id: account.id,
                owner_name: member.display_name.clone(),
                name: account.name.clone(),
                account_type: account.account_type.as_str().to_owned(),
                currency: account.currency.clone(),
                balance: account.current_balance,
                balance_eur,
            });
        }

        let goals = shared_goals(&mut conn, member.id).await?;
        if goals.is_empty() {
            continue;
        }
        let goal_ids: Vec<i64> = goals.iter().map(|g| g.id).collect();
        let linked = goal_accounts(&mut conn, &goal_ids).await?;
        let mut distinct: Vec<Account> = Vec::new();
        for account in linked.values().flatten() {
            if !distinct.iter().any(|a| a.id == account.id) {
                distinct.push(account.clone());
            }
        }
        let goal_shares = access::shares_for(&mut *conn, &distinct, member.id).await?;
        for goal in goals {
            let mut current_total = Decimal::ZERO;
            for account in linked.get(&goal.id).map(Vec::as_slice).unwrap_or_default() {
                let signed =
                    valuation::signed_live_balance_eur(&mut conn, prices, account, today).await?;
                current_total += access::weigh(
                    signed,
                    goal_shares.get(&account.id).copied().unwrap_or_default(),
                );
            }
            let contributions = contributions_of(&mut conn, goal.id, &all_members).await?;
            shared_goal_infos.push(SharedGoalInfo {
                id: goal.id,
                owner_name: member.display_name.clone(),
                name: goal.name,
                target_amount: goal.target_amount,
                current_total,
                contributions,
            });
        }
    }

    Ok(axum::Json(FamilyDashboardResponse {
        shared_accounts: shared_account_infos,
        shared_goals: shared_goal_infos,
        total_shared_net_worth: total,
    }))
}

/// Whether the owner shares this goal with the family.
async fn goal_visible(conn: &mut PgConnection, goal: &GoalRow) -> sqlx::Result<bool> {
    Ok(
        match sharing::level(&mut *conn, goal.member_id, GOAL).await? {
            SharingLevel::None => false,
            SharingLevel::All => true,
            SharingLevel::Manual => {
                sharing::is_shared(&mut *conn, goal.member_id, GOAL, goal.id).await?
            }
        },
    )
}

async fn contributions(
    State(state): State<AppState>,
    auth: Auth,
    Path(goal_id): Path<i64>,
) -> AppResult<axum::Json<Vec<ContributionInfo>>> {
    let mut conn = state.db.acquire().await?;
    let all_members = members::find_all(&mut *conn).await?;
    let goal: GoalRow = sqlx::query_as(&format!("SELECT {GOAL_COLUMNS} FROM goal WHERE id = $1"))
        .bind(goal_id)
        .fetch_optional(&mut *conn)
        .await?
        .ok_or_else(|| AppError::NotFound("Goal not found".into()))?;
    if goal.member_id != auth.member_id && !goal_visible(&mut conn, &goal).await? {
        return Err(AppError::Forbidden("Goal not accessible".into()));
    }
    Ok(axum::Json(
        contributions_of(&mut conn, goal_id, &all_members).await?,
    ))
}
