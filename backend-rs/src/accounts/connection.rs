//! AccountConnectionService: deleting an account also removes the sync connection feeding it
//! once no other live account is left on it, so the sync stops rebuilding what was deleted.

use std::collections::HashSet;

use axum::extract::State;
use axum::http::StatusCode;
use serde::Serialize;
use sqlx::PgConnection;

use super::meta::detach_scpi_funds;
use super::seams::{ConnectionKind, ConnectionRef};
use crate::AppState;
use crate::auth::Auth;
use crate::error::{AppError, AppResult};
use crate::http::json::Path;
use crate::ledger::accounts;
use crate::ledger::locks::LockedConnection;
use crate::ledger::model::{Account, CORUM_LIFE_EXTERNAL_ID_PREFIX};

#[serde_with::skip_serializing_none]
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DeletionImpact {
    pub removes_connection: bool,
    pub connection_label: Option<String>,
}

impl DeletionImpact {
    fn none() -> Self {
        Self {
            removes_connection: false,
            connection_label: None,
        }
    }
}

/// Accounts of the member's SCPI positions linked to a CORUM, then a Louve fund.
struct ScpiLinks {
    corum: HashSet<i64>,
    louve: HashSet<i64>,
}

async fn scpi_links(conn: &mut PgConnection, member_id: i64) -> sqlx::Result<ScpiLinks> {
    let rows: Vec<(i64, bool, bool)> = sqlx::query_as(
        "SELECT p.account_id, p.corum_fund_code IS NOT NULL, p.louve_fund_code IS NOT NULL FROM scpi_position p \
         JOIN account a ON a.id = p.account_id AND a.deleted_at IS NULL WHERE a.member_id = $1",
    )
    .bind(member_id)
    .fetch_all(conn)
    .await?;
    Ok(ScpiLinks {
        corum: rows.iter().filter(|r| r.1).map(|r| r.0).collect(),
        louve: rows.iter().filter(|r| r.2).map(|r| r.0).collect(),
    })
}

/// The external-id prefixes of the syncs that own one connection per member.
const SINGLETON_PREFIXES: [(&str, ConnectionKind); 13] = [
    ("amundi_", ConnectionKind::Amundi),
    ("cmes_", ConnectionKind::Cmes),
    ("cic_", ConnectionKind::Cic),
    ("natixis_", ConnectionKind::Natixis),
    ("tr_", ConnectionKind::TradeRepublic),
    ("bd_", ConnectionKind::BourseDirect),
    ("bourso_", ConnectionKind::Bourso),
    ("ft_", ConnectionKind::Fortuneo),
    ("ibkr_", ConnectionKind::Ibkr),
    ("sfin_", ConnectionKind::Simplefin),
    ("wise_jar_", ConnectionKind::Wise),
    ("lbp_", ConnectionKind::Lbp),
    ("bnp_", ConnectionKind::Bnp),
];

fn singleton(kind: ConnectionKind) -> Option<ConnectionRef> {
    Some(ConnectionRef {
        kind,
        discriminator: String::new(),
    })
}

fn resolve(account: &Account, links: &ScpiLinks) -> Option<ConnectionRef> {
    if let Some(requisition) = account.requisition_id {
        return Some(ConnectionRef {
            kind: ConnectionKind::EnableBanking,
            discriminator: requisition.to_string(),
        });
    }
    if let Some(external) = account.external_account_id.as_deref() {
        if external.starts_with("wallet_") {
            let wallet = external.rsplit('_').next().unwrap_or_default();
            return Some(ConnectionRef {
                kind: ConnectionKind::Wallet,
                discriminator: wallet.to_owned(),
            });
        }
        if let Some(exchange) = external.strip_prefix("crypto_exchange_") {
            return Some(ConnectionRef {
                kind: ConnectionKind::Exchange,
                discriminator: exchange.to_uppercase(),
            });
        }
        if let Some((_, kind)) = SINGLETON_PREFIXES
            .iter()
            .find(|(p, _)| external.starts_with(p))
        {
            return singleton(*kind);
        }
        if external == "degiro-portfolio" {
            return singleton(ConnectionKind::Degiro);
        }
        if external.starts_with(CORUM_LIFE_EXTERNAL_ID_PREFIX) {
            return singleton(ConnectionKind::Corum);
        }
    }
    if links.corum.contains(&account.id) {
        return singleton(ConnectionKind::Corum);
    }
    if links.louve.contains(&account.id) {
        return singleton(ConnectionKind::Louve);
    }
    None
}

async fn has_other_live_account(
    conn: &mut PgConnection,
    connection: &ConnectionRef,
    deleted_id: i64,
    member_id: i64,
    links: &ScpiLinks,
) -> sqlx::Result<bool> {
    Ok(accounts::find_all_by_member(conn, member_id)
        .await?
        .iter()
        .filter(|other| other.id != deleted_id)
        .any(|other| resolve(other, links).as_ref() == Some(connection)))
}

async fn label(
    conn: &mut PgConnection,
    connection: &ConnectionRef,
    account: &Account,
    member_id: i64,
) -> sqlx::Result<String> {
    use ConnectionKind as K;
    Ok(match connection.kind {
        K::Wallet => {
            let wallet: Option<(Option<String>, String)> =
                match connection.discriminator.parse::<i64>() {
                    Ok(id) => sqlx::query_as(
                        "SELECT label, chain FROM wallet_address WHERE id = $1 AND member_id = $2",
                    )
                    .bind(id)
                    .bind(member_id)
                    .fetch_optional(conn)
                    .await?,
                    Err(_) => None,
                };
            match wallet {
                Some((Some(label), _)) => label,
                Some((None, chain)) => format!("{chain} Wallet"),
                None => account.name.clone(),
            }
        }
        K::Exchange => connection.discriminator.clone(),
        K::Amundi => "Amundi".to_owned(),
        K::Cmes => "Crédit Mutuel / CIC Épargne Salariale".to_owned(),
        K::Cic => "CIC".to_owned(),
        K::Natixis => "Natixis Interépargne".to_owned(),
        K::TradeRepublic => "Trade Republic".to_owned(),
        K::BourseDirect => "Bourse Direct".to_owned(),
        K::Bourso => "BoursoBank".to_owned(),
        K::Fortuneo => "Fortuneo".to_owned(),
        K::Degiro => "DEGIRO".to_owned(),
        K::Corum => "CORUM".to_owned(),
        K::Louve => "Louve Invest".to_owned(),
        K::Lbp => "La Banque Postale".to_owned(),
        K::Bnp => "BNP Paribas".to_owned(),
        K::Ibkr => "Interactive Brokers".to_owned(),
        K::Simplefin => "SimpleFIN".to_owned(),
        K::Wise => "Wise Jars".to_owned(),
        K::EnableBanking => {
            let name: Option<Option<String>> =
                match connection.discriminator.parse::<i64>() {
                    Ok(id) => sqlx::query_scalar(
                        "SELECT institution_name FROM requisition WHERE id = $1 AND member_id = $2",
                    )
                    .bind(id)
                    .bind(member_id)
                    .fetch_optional(conn)
                    .await?,
                    Err(_) => None,
                };
            match name {
                Some(name) => name.unwrap_or_default(),
                None => account.provider.clone().unwrap_or_default(),
            }
        }
    })
}

pub async fn deletion_impact(
    State(state): State<AppState>,
    auth: Auth,
    Path(id): Path<i64>,
) -> AppResult<axum::Json<DeletionImpact>> {
    let mut conn = state.db.acquire().await?;
    let account = find_owned(&mut conn, id, auth.member_id).await?;
    let links = scpi_links(&mut conn, auth.member_id).await?;
    let Some(connection) = resolve(&account, &links) else {
        return Ok(axum::Json(DeletionImpact::none()));
    };
    if has_other_live_account(&mut conn, &connection, id, auth.member_id, &links).await? {
        return Ok(axum::Json(DeletionImpact::none()));
    }
    Ok(axum::Json(DeletionImpact {
        removes_connection: true,
        connection_label: Some(label(&mut conn, &connection, &account, auth.member_id).await?),
    }))
}

async fn find_owned(conn: &mut PgConnection, id: i64, member_id: i64) -> AppResult<Account> {
    accounts::find_by_id_and_member(conn, id, member_id)
        .await?
        .ok_or_else(|| AppError::NotFound("Account not found".to_owned()))
}

pub async fn delete_account(
    State(state): State<AppState>,
    auth: Auth,
    Path(id): Path<i64>,
) -> AppResult<StatusCode> {
    let member_id = auth.member_id;
    let mut tx = state.db.begin().await?;
    let account = find_owned(&mut tx, id, member_id).await?;
    let mut links = scpi_links(&mut tx, member_id).await?;
    let connection = resolve(&account, &links);
    let locked = match connection.as_ref().map(|c| c.kind) {
        Some(ConnectionKind::Corum) => Some(LockedConnection::Corum),
        Some(ConnectionKind::Louve) => Some(LockedConnection::Louve),
        _ => None,
    };
    if let Some(locked) = locked {
        // Serialize with a running CORUM or Louve sync, then read its links again.
        state
            .ledger
            .connection_locks
            .lock_member_for_account_deletion(&mut tx, locked, member_id)
            .await?;
        links = scpi_links(&mut tx, member_id).await?;
    }
    soft_delete(
        &mut tx,
        &account,
        member_id,
        state.clock.now(),
        state.clock.zone(),
    )
    .await?;
    if let Some(connection) = connection
        && !has_other_live_account(&mut tx, &connection, id, member_id, &links).await?
    {
        tracing::info!(
            "Removing {:?} connection {} -- its last account was deleted",
            connection.kind,
            connection.discriminator
        );
        if connection.kind == ConnectionKind::EnableBanking {
            if let Ok(requisition) = connection.discriminator.parse::<i64>() {
                sqlx::query("DELETE FROM requisition WHERE id = $1 AND member_id = $2")
                    .bind(requisition)
                    .bind(member_id)
                    .execute(&mut *tx)
                    .await?;
            }
        } else {
            state
                .accounts
                .connections
                .remove(&mut tx, &connection, member_id)
                .await?;
        }
    }
    tx.commit().await?;
    Ok(StatusCode::NO_CONTENT)
}

/// AccountService.delete: soft-deletes the row after detaching what still points at it.
async fn soft_delete(
    conn: &mut PgConnection,
    account: &Account,
    member_id: i64,
    now: chrono::DateTime<chrono::Utc>,
    zone: chrono_tz::Tz,
) -> AppResult<()> {
    // The fund-code unique indexes do not exclude soft-deleted rows, and a sync would recreate
    // the account of a fund it has not seen: detach and tombstone the funds.
    detach_scpi_funds(&mut *conn, account.id, member_id).await?;
    // ON DELETE SET NULL never fires on a soft delete.
    sqlx::query("UPDATE debt SET linked_account_id = NULL WHERE linked_account_id = $1")
        .bind(account.id)
        .execute(&mut *conn)
        .await?;
    sqlx::query("UPDATE peer_loan_terms SET linked_account_id = NULL WHERE linked_account_id = $1")
        .bind(account.id)
        .execute(&mut *conn)
        .await?;
    // deleted_at is a TIMESTAMP without zone, written as Hibernate writes an Instant there: the
    // wall-clock time of the JVM zone, which TimeZoneConfig pins to app.timezone.
    sqlx::query("UPDATE account SET deleted_at = $2, updated_at = $3 WHERE id = $1")
        .bind(account.id)
        .bind(now.with_timezone(&zone).naive_local())
        .bind(now)
        .execute(&mut *conn)
        .await?;
    Ok(())
}
