//! AccountOwnershipService: how a real estate, SCPI or loan account splits between members.

use crate::db::AsDbHandle;
use crate::db::DbHandle;
use axum::extract::State;
use axum::http::StatusCode;
use rust_decimal::Decimal;
use serde::{Deserialize, Serialize};

use super::jdecimal;
use super::validation::{Check, ValidJson, ValidateAt};
use crate::AppState;
use crate::auth::Auth;
use crate::db;
use crate::error::{AppError, AppResult};
use crate::http::json::Path;
use crate::ledger::model::{Account, AccountType, FamilyMember};
use crate::ledger::{access, members};

const FULL: Decimal = Decimal::from_parts(100, 0, 0, false, 0);

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct OwnershipRequest {
    shares: Option<Vec<ShareRequest>>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct ShareRequest {
    member_id: Option<i64>,
    share_percent: Option<Decimal>,
}

impl ValidateAt for OwnershipRequest {
    fn validate_at(&self, check: &mut Check<'_>) {
        check.errors.not_null("shares", self.shares.as_ref());
        for (i, share) in self.shares.iter().flatten().enumerate() {
            let member = format!("shares[{i}].memberId");
            let percent = format!("shares[{i}].sharePercent");
            check
                .errors
                .not_null(&member, share.member_id.as_ref())
                .not_null(&percent, share.share_percent.as_ref());
            check
                .decimal_min(&percent, share.share_percent, "0", false)
                .decimal_max(&percent, share.share_percent, "100");
        }
    }
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct OwnershipResponse {
    shares: Vec<MemberShare>,
    #[serde(with = "jdecimal")]
    total_assigned: Decimal,
    #[serde(with = "jdecimal")]
    unassigned: Decimal,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct MemberShare {
    member_id: i64,
    display_name: String,
    avatar_color: String,
    #[serde(with = "jdecimal")]
    share_percent: Decimal,
    is_owner: bool,
}

fn unprocessable(detail: &str) -> AppError {
    AppError::Status {
        status: StatusCode::UNPROCESSABLE_ENTITY,
        detail: detail.to_owned(),
    }
}

async fn member(conn: &mut DbHandle<'_>, id: i64) -> AppResult<FamilyMember> {
    members::find_by_id(conn, id)
        .await?
        .ok_or_else(|| AppError::NotFound("Family member not found".to_owned()))
}

fn owner_only(owner: &FamilyMember) -> OwnershipResponse {
    OwnershipResponse {
        shares: vec![MemberShare {
            member_id: owner.id,
            display_name: owner.display_name.clone(),
            avatar_color: owner.avatar_color.clone(),
            share_percent: FULL,
            is_owner: true,
        }],
        total_assigned: FULL,
        unassigned: Decimal::ZERO,
    }
}

fn split(owner_id: i64, rows: Vec<(FamilyMember, Decimal)>) -> OwnershipResponse {
    let total = rows
        .iter()
        .map(|(_, s)| *s)
        .fold(Decimal::ZERO, super::scale::add);
    OwnershipResponse {
        shares: rows
            .into_iter()
            .map(|(m, share)| MemberShare {
                is_owner: m.id == owner_id,
                member_id: m.id,
                display_name: m.display_name,
                avatar_color: m.avatar_color,
                share_percent: share,
            })
            .collect(),
        // Java's sum starts at BigDecimal.ZERO, so the scale is the widest share's.
        total_assigned: total,
        unassigned: super::scale::sub(FULL, total),
    }
}

pub async fn get_ownership(
    State(state): State<AppState>,
    auth: Auth,
    Path(id): Path<i64>,
) -> AppResult<axum::Json<OwnershipResponse>> {
    let mut conn = state.db.acquire().await?;
    let account = super::scale::fixed(
        access::require_readable(&mut conn.as_handle(), id, auth.member_id).await?,
    );
    let owner = member(&mut conn.as_handle(), account.member_id).await?;
    let rows = access::ownerships_of_account(&mut conn, id).await?;
    if rows.is_empty() {
        return Ok(axum::Json(owner_only(&owner)));
    }
    let mut shares = Vec::with_capacity(rows.len());
    for row in rows {
        shares.push((
            member(&mut conn.as_handle(), row.member_id).await?,
            super::scale::column(row.share_percent, 3),
        ));
    }
    Ok(axum::Json(split(owner.id, shares)))
}

fn splittable(account: &Account) -> bool {
    matches!(
        account.account_type,
        AccountType::RealEstate | AccountType::Loan | AccountType::Scpi
    )
}

pub async fn replace_ownership(
    State(state): State<AppState>,
    auth: Auth,
    Path(id): Path<i64>,
    ValidJson(req): ValidJson<OwnershipRequest>,
) -> AppResult<axum::Json<OwnershipResponse>> {
    let mut tx = state.db.begin().await?;
    let account =
        super::scale::fixed(access::require_owner(&mut tx.as_handle(), id, auth.member_id).await?);
    if !splittable(&account) {
        return Err(unprocessable(
            "Ownership shares are only supported on real estate, SCPI and loan accounts",
        ));
    }
    let shares: Vec<(i64, Decimal)> = req
        .shares
        .unwrap_or_default()
        .into_iter()
        .map(|s| {
            (
                s.member_id.unwrap_or_default(),
                s.share_percent.unwrap_or_default(),
            )
        })
        .collect();
    let owner = member(&mut tx.as_handle(), account.member_id).await?;
    if !shares.is_empty() {
        let mut seen = std::collections::HashSet::new();
        let mut total = Decimal::ZERO;
        for (member_id, share) in &shares {
            if !seen.insert(*member_id) {
                return Err(unprocessable(
                    "A member appears twice in the ownership split",
                ));
            }
            total = super::scale::add(total, *share);
        }
        if total > FULL {
            return Err(unprocessable("Ownership shares add up to more than 100%"));
        }
        if !seen.contains(&owner.id) {
            return Err(unprocessable("The account owner must keep a share"));
        }
    }
    access::delete_ownerships_of_account(&mut tx, id).await?;
    if shares.is_empty() {
        tx.commit().await?;
        return Ok(axum::Json(owner_only(&owner)));
    }
    let mut rows = Vec::with_capacity(shares.len());
    for (member_id, share) in shares {
        let m = member(&mut tx.as_handle(), member_id).await?;
        db::query(
            "INSERT INTO account_ownership (account_id, member_id, share_percent, created_at, updated_at) \
             VALUES ($1, $2, $3, NOW(), NOW())",
        )
        .bind(id)
        .bind(member_id)
        .bind(share)
        .execute(&mut tx)
        .await?;
        rows.push((m, share));
    }
    tx.commit().await?;
    Ok(axum::Json(split(owner.id, rows)))
}
