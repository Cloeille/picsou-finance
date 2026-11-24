//! WalletSyncService: add, sync, resync, list and remove on-chain wallets.

use std::collections::BTreeSet;

use crate::db::AsDbHandle;
use crate::db::DbHandle;
use chrono::{DateTime, NaiveDate, Utc};
use http::StatusCode;
use rust_decimal::Decimal;

use super::repo::{self, Wallet};
use super::response::{self, AccountResponse};
use super::rpc::{WalletBalance, WalletError};
use super::{Chain, bitcoin, evm, solana};
use crate::AppState;
use crate::error::{AppError, AppResult};
use crate::ledger::accounts::{self, NewAccount};
use crate::ledger::decimal::{HALF_UP, set_scale};
use crate::ledger::model::{Account, AccountType};
use crate::ledger::prices::PriceNeeds;
use crate::ledger::sync_values::{clean, java_trim};
use crate::ledger::valuation::{self, SnapshotContext};
use crate::ledger::{holdings, members};

/// The wallet_address.address column width.
const MAX_ADDRESS_LENGTH: usize = 200;

/// The bundled logo a new wallet account starts with: its provider is the native ticker, which
/// the frontend's provider logo map cannot key on. Written on creation only.
pub const DEFAULT_LOGO_KEY: &str = "blockchain";

const WALLET_COLOR: &str = "#f59e0b";

/// Outcome of a batch resync: wallets tried, wallets synced, and the chains that failed. A
/// wallet whose account the user deleted is neither synced nor failed.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ResyncSummary {
    pub total: usize,
    pub succeeded: usize,
    pub failed: Vec<Chain>,
}

fn wallet_not_found() -> AppError {
    AppError::NotFound("Wallet not found".into())
}

fn sync_failed(chain: Chain) -> AppError {
    AppError::status(
        StatusCode::UNPROCESSABLE_ENTITY,
        format!("Could not sync your {chain} wallet. Please try again later."),
    )
}

fn external_id(wallet: &Wallet) -> String {
    format!(
        "wallet_{}_{}",
        wallet.chain.as_str().to_lowercase(),
        wallet.id
    )
}

/// The add-wallet body once bound: absent fields are None.
pub struct NewWallet {
    pub chain: Option<Chain>,
    pub address: Option<String>,
    pub label: Option<String>,
}

pub async fn add_wallet(
    state: &AppState,
    request: NewWallet,
    member_id: i64,
) -> AppResult<AccountResponse> {
    let chain = request
        .chain
        .ok_or_else(|| AppError::BadRequest("A wallet chain is required".into()))?;
    let address = java_trim(request.address.as_deref().unwrap_or("")).to_owned();
    if address.is_empty() {
        return Err(AppError::BadRequest("A wallet address is required".into()));
    }
    let length = address.encode_utf16().count();
    if length > MAX_ADDRESS_LENGTH {
        return Err(AppError::BadRequest(format!(
            "Wallet address is too long ({length} characters, maximum {MAX_ADDRESS_LENGTH})"
        )));
    }
    if chain == Chain::Evm {
        evm::validate_address(&address).map_err(AppError::BadRequest)?;
    }
    members::find_by_id(&state.db, member_id)
        .await?
        .ok_or_else(|| AppError::NotFound("Family member not found".into()))?;

    let now = state.clock.now();
    let label = clean(request.label.as_deref());
    // Fetch on-chain balances and refresh live prices BEFORE opening the write transaction so
    // SQLite's writer gate is never held across RPC/HTTP calls or re-entered by PriceService's
    // price_snapshot upserts. A wallet that cannot sync is never inserted.
    let prepared = prepare(state, chain, &address, None).await?;
    let mut tx = state.db.begin().await?;
    let wallet = repo::insert(&mut tx, member_id, chain, &address, label.as_deref(), now).await?;
    let account = write(state, &mut tx.as_handle(), &wallet, &prepared, member_id)
        .await?
        .ok_or_else(account_deleted)?;
    tx.commit().await?;
    response::build(
        &mut state.db.pool_handle(),
        &*state.ledger.prices,
        &account,
        state.clock.today(),
    )
    .await
}

fn account_deleted() -> AppError {
    AppError::status(
        StatusCode::UNPROCESSABLE_ENTITY,
        "This wallet's account was deleted. Remove the wallet from Sync -> Wallets to stop tracking it.",
    )
}

/// The user-triggered sync of one wallet. A wallet whose account was deleted can never produce
/// one again, so it is a 422 naming the way out rather than a silent success.
pub async fn sync(state: &AppState, wallet_id: i64, member_id: i64) -> AppResult<AccountResponse> {
    let prepared = prepare_wallet(state, wallet_id, member_id).await?;
    let mut tx = state.db.begin().await?;
    let account = sync_locked(state, &mut tx.as_handle(), wallet_id, member_id, &prepared)
        .await?
        .ok_or_else(account_deleted)?;
    tx.commit().await?;
    response::build(
        &mut state.db.pool_handle(),
        &*state.ledger.prices,
        &account,
        state.clock.today(),
    )
    .await
}

/// Reads the wallet and fetches chain balances + live prices outside any write transaction.
async fn prepare_wallet(state: &AppState, wallet_id: i64, member_id: i64) -> AppResult<Prepared> {
    let wallet = repo::find_by_id_and_member(&state.db, wallet_id, member_id)
        .await?
        .ok_or_else(wallet_not_found)?;
    let prepared = prepare(state, wallet.chain, &wallet.address, Some(wallet.id)).await?;
    // The account is valued as stored too, with a currency the member may have set.
    let stored = valuation::needs_of_external(
        &mut state.db.pool_handle(),
        member_id,
        &external_id(&wallet),
    )
    .await?;
    state
        .ledger
        .prices
        .prefetch(&stored, state.clock.today())
        .await;
    Ok(prepared)
}

/// Applies a prepared wallet sync inside the caller's transaction; None when its account was
/// soft-deleted.
async fn sync_locked(
    state: &AppState,
    conn: &mut DbHandle<'_>,
    wallet_id: i64,
    member_id: i64,
    prepared: &Prepared,
) -> AppResult<Option<Account>> {
    // Re-read under a row lock: the wallet may have been removed while the chain answered.
    let wallet = repo::lock(&mut *conn, wallet_id, member_id)
        .await?
        .ok_or_else(wallet_not_found)?;
    write(state, conn, &wallet, prepared, member_id).await
}

/// MemberSyncService's "wallets" source: every wallet of the member, each in its own
/// transaction, one failure never stopping the others.
pub async fn resync_all(state: &AppState, member_id: i64) -> ResyncSummary {
    let wallets = match repo::find_all_by_member(&state.db, member_id).await {
        Ok(wallets) => wallets,
        Err(e) => {
            tracing::error!("Could not list the wallets of member {member_id}: {e}");
            return ResyncSummary {
                total: 0,
                succeeded: 0,
                failed: Vec::new(),
            };
        }
    };
    let mut summary = ResyncSummary {
        total: wallets.len(),
        succeeded: 0,
        failed: Vec::new(),
    };
    for wallet in wallets {
        match resync_one(state, wallet.id, member_id).await {
            Ok(true) => summary.succeeded += 1,
            Ok(false) => tracing::info!("Wallet {} skipped: its account was deleted", wallet.id),
            Err(e) => {
                tracing::error!(
                    "Wallet resync failed for {} wallet {}: {e}",
                    wallet.chain,
                    wallet.id
                );
                summary.failed.push(wallet.chain);
            }
        }
    }
    summary
}

async fn resync_one(state: &AppState, wallet_id: i64, member_id: i64) -> AppResult<bool> {
    let prepared = prepare_wallet(state, wallet_id, member_id).await?;
    let mut tx = state.db.begin().await?;
    let synced = sync_locked(state, &mut tx.as_handle(), wallet_id, member_id, &prepared)
        .await?
        .is_some();
    tx.commit().await?;
    Ok(synced)
}

pub async fn list_wallets(state: &AppState, member_id: i64) -> AppResult<Vec<Wallet>> {
    repo::find_all_by_member(&state.db, member_id).await
}

/// Removes the member's wallet and soft-deletes its account: a hard delete would cascade over
/// the account's balance history, and the soft-deleted row keeps the resurrection guard armed.
/// Also what deleting the wallet's last account calls (AccountConnectionService).
pub async fn remove_wallet(
    conn: &mut DbHandle<'_>,
    now: DateTime<Utc>,
    wallet_id: i64,
    member_id: i64,
) -> AppResult<()> {
    let wallet = repo::lock(&mut *conn, wallet_id, member_id)
        .await?
        .ok_or_else(wallet_not_found)?;
    if let Some(account) =
        accounts::find_by_external_id_and_member(&mut *conn, &external_id(&wallet), member_id)
            .await?
    {
        // Not accounts::update: the row it returns carries deleted_at, a TIMESTAMP column the
        // ledger model decodes as TIMESTAMPTZ. Written as the UTC instant, as Hibernate does.
        crate::db::query(
            "UPDATE account SET deleted_at = ($2::timestamptz AT TIME ZONE 'UTC'), updated_at = $2 WHERE id = $1",
        )
        .bind(account.id)
        .bind(now)
        .execute(&mut *conn)
        .await?;
    }
    repo::delete(&mut *conn, wallet.id).await?;
    tracing::info!("Removed wallet {wallet_id} and soft-deleted its account");
    Ok(())
}

/// A balance with its ticker and EUR price, resolved once for the total and the holdings.
struct Priced {
    ticker: String,
    amount: Decimal,
    price_eur: Option<Decimal>,
}

/// What the chain and the price service said, before anything is written.
struct Prepared {
    native_symbol: String,
    priced: Vec<Priced>,
    balance_eur: Decimal,
}

async fn fetch_balances(
    state: &AppState,
    chain: Chain,
    address: &str,
) -> Result<Vec<WalletBalance>, WalletError> {
    match chain {
        Chain::Evm => evm::fetch_balances(state, address).await,
        Chain::Bitcoin => bitcoin::fetch_balances(state, address).await,
        Chain::Solana => solana::fetch_balances(state, address).await,
    }
}

async fn prepare(
    state: &AppState,
    chain: Chain,
    address: &str,
    wallet_id: Option<i64>,
) -> AppResult<Prepared> {
    let today = state.clock.today();
    match try_prepare(state, chain, address, today).await {
        Ok(prepared) => Ok(prepared),
        Err(e) => {
            let id_label = wallet_id.map_or_else(|| "new".to_owned(), |id| id.to_string());
            if e.expected {
                tracing::warn!(
                    "Wallet sync failed for {chain} wallet {id_label}: {}",
                    e.message
                );
            } else {
                tracing::error!(
                    "Unexpected error during wallet sync for {chain} wallet {id_label}: {}",
                    e.message
                );
            }
            Err(sync_failed(chain))
        }
    }
}

async fn try_prepare(
    state: &AppState,
    chain: Chain,
    address: &str,
    today: NaiveDate,
) -> Result<Prepared, WalletError> {
    let balances = fetch_balances(state, chain, address).await?;
    let Some(first) = balances.first() else {
        return Err(WalletError::rpc(format!(
            "Adapter returned no balances for {chain}"
        )));
    };
    // The chain's native asset leads by contract; it is the account's display ticker.
    let native_symbol = first.symbol.clone();
    let tickers: BTreeSet<String> = balances.iter().map(|b| b.symbol.to_uppercase()).collect();
    let prices = state
        .sync
        .wallets
        .prices()
        .refresh_prices(&tickers, today)
        .await;

    let priced: Vec<Priced> = balances
        .into_iter()
        .map(|b| {
            let ticker = b.symbol.to_uppercase();
            let price_eur = prices.get(&ticker).copied();
            Priced {
                ticker,
                amount: b.amount,
                price_eur,
            }
        })
        .collect();

    let mut balance_eur = Decimal::ZERO;
    let mut any_held = false;
    let mut any_priced = false;
    for p in &priced {
        any_held |= p.amount > Decimal::ZERO;
        match p.price_eur {
            Some(price) => {
                any_priced = true;
                balance_eur += set_scale(p.amount * price, 2, HALF_UP);
            }
            None if p.amount > Decimal::ZERO => {
                tracing::warn!("No EUR price for {} -- skipping in wallet total", p.ticker);
            }
            None => {}
        }
    }
    // Holding assets yet pricing none means the price service is down, not an empty wallet:
    // recording the zero would corrupt the net-worth history. A partial total is still written.
    if any_held && !any_priced {
        return Err(WalletError::rpc(format!(
            "No EUR price available for any asset held in this wallet -- refusing to record a zero balance for {chain}"
        )));
    }
    // The snapshot written with these balances values the wallet's holdings through the price
    // seam: asked here, so the write transaction finds the answers in memory and awaits no
    // provider while it holds SQLite's only writer.
    let mut needs = PriceNeeds::default();
    needs.account(
        AccountType::Crypto,
        "EUR",
        None,
        priced
            .iter()
            .filter(|p| p.amount > Decimal::ZERO)
            .map(|p| p.ticker.as_str()),
    );
    state.ledger.prices.prefetch(&needs, today).await;
    Ok(Prepared {
        native_symbol,
        priced,
        balance_eur,
    })
}

/// Everything a successful fetch writes. Any failure here is still the friendly 422, as Java's
/// catch-all around the whole sync makes it.
async fn write(
    state: &AppState,
    conn: &mut DbHandle<'_>,
    wallet: &Wallet,
    prepared: &Prepared,
    member_id: i64,
) -> AppResult<Option<Account>> {
    match try_write(state, conn, wallet, prepared, member_id).await {
        Ok(account) => Ok(account),
        Err(e) => {
            tracing::error!(
                "Unexpected error during wallet sync for {} wallet {}: {e}",
                wallet.chain,
                wallet.id
            );
            Err(sync_failed(wallet.chain))
        }
    }
}

async fn try_write(
    state: &AppState,
    conn: &mut DbHandle<'_>,
    wallet: &Wallet,
    prepared: &Prepared,
    member_id: i64,
) -> AppResult<Option<Account>> {
    let now = state.clock.now();
    let today = state.clock.today();
    repo::set_last_synced(&mut *conn, wallet.id, now).await?;

    let Some(mut account) = resolve_account(conn, wallet, prepared, member_id, now).await? else {
        return Ok(None);
    };
    // The response shows the balance as computed, as the saved JPA entity holds it, not the
    // column's padded scale.
    account.current_balance = prepared.balance_eur;

    // Every asset held on-chain keeps its holding, priced this cycle or not: a price outage must
    // never delete a holding and its cost basis. Only priced ones are refreshed.
    let mut kept: Vec<String> = Vec::new();
    for p in prepared.priced.iter().filter(|p| p.amount > Decimal::ZERO) {
        if !kept.contains(&p.ticker) {
            kept.push(p.ticker.clone());
        }
        if let Some(price) = p.price_eur {
            holdings::upsert_holding(
                &mut *conn,
                account.id,
                member_id,
                &p.ticker,
                Some(&p.ticker),
                p.amount,
                Some(price),
                now,
            )
            .await?;
        }
    }
    holdings::prune_holdings(&mut *conn, account.id, &kept).await?;

    let ctx = SnapshotContext {
        prices: &*state.ledger.prices,
        today,
        now,
    };
    valuation::upsert_snapshot(&mut *conn, &ctx, &account, prepared.balance_eur, today).await?;
    Ok(Some(account))
}

/// The wallet's account, created on first sync; None when the user soft-deleted it, since the
/// chain returning the address forever is not consent to bring it back.
async fn resolve_account(
    conn: &mut DbHandle<'_>,
    wallet: &Wallet,
    prepared: &Prepared,
    member_id: i64,
    now: DateTime<Utc>,
) -> AppResult<Option<Account>> {
    let external_id = external_id(wallet);
    if let Some(mut account) =
        accounts::find_by_external_id_and_member(&mut *conn, &external_id, member_id).await?
    {
        account.current_balance = prepared.balance_eur;
        account.last_synced_at = Some(now);
        // No ticker: the balance is already EUR and a ticker would convert it a second time.
        account.ticker = None;
        return Ok(Some(accounts::update(&mut *conn, &account, now).await?));
    }
    if accounts::exists_soft_deleted_by_external_id(&mut *conn, &external_id, member_id).await? {
        tracing::info!(
            "Skipping resurrection of soft-deleted wallet account externalId={external_id} member={member_id}"
        );
        return Ok(None);
    }
    members::find_by_id(&mut *conn, member_id)
        .await?
        .ok_or_else(|| AppError::NotFound("Family member not found".into()))?;

    let mut draft = NewAccount::new(
        member_id,
        wallet
            .label
            .clone()
            .unwrap_or_else(|| format!("{} Wallet", wallet.chain)),
        AccountType::Crypto,
    );
    draft.provider = Some(prepared.native_symbol.clone());
    draft.current_balance = prepared.balance_eur;
    draft.last_synced_at = Some(now);
    draft.external_account_id = Some(external_id.clone());
    draft.is_manual = false;
    draft.color = WALLET_COLOR.to_owned();
    draft.logo_key = Some(DEFAULT_LOGO_KEY.to_owned());

    // A concurrent sync of the same wallet may insert first: reuse its row rather than split the
    // snapshot history over two accounts.
    let mut savepoint = conn.begin().await?;
    match accounts::insert(&mut savepoint, &draft, now).await {
        Ok(account) => {
            savepoint.commit().await?;
            Ok(Some(account))
        }
        Err(e) if crate::db::is_unique_violation(&e) => {
            savepoint.rollback().await?;
            tracing::warn!(
                "Concurrent account creation for {external_id} -- reusing the winning row"
            );
            accounts::find_by_external_id_and_member(&mut *conn, &external_id, member_id)
                .await?
                .map(Some)
                .ok_or_else(|| AppError::internal("the concurrent wallet account vanished"))
        }
        Err(e) => Err(e.into()),
    }
}
