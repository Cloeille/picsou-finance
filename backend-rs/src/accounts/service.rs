//! AccountController's account endpoints: list, read, create, update, visibility, display
//! preferences, balance history and the account's transactions.

use std::collections::{HashMap, HashSet};
use std::sync::LazyLock;

use axum::extract::{RawQuery, State};
use axum::http::StatusCode;
use chrono::{DateTime, Months, NaiveDate, Utc};
use rust_decimal::Decimal;
use serde::{Deserialize, Serialize};

use super::dto::{AccountResponse, responses_for_viewer, to_response};
use super::jdecimal;
use super::query::Params;
use super::validation::{Check, ValidJson, ValidateAt};
use crate::AppState;
use crate::auth::Auth;
use crate::db::{self, AsDbHandle, DbExec, DbHandle};
use crate::error::{AppError, AppResult};
use crate::http::json::{Errors, Json, Path, Validate, null_as_false};
use crate::ledger::manual_transactions::{
    self, ManualEntryContext, PeerLoanLink, PeerLoanLinkKind, TransactionRequest,
    TransactionResponse,
};
use crate::ledger::model::{
    Account, AccountDisplayPref, AccountType, BalanceSnapshot, SnapshotSource,
};
use crate::ledger::snapshots::NewSnapshot;
use crate::ledger::valuation::SnapshotContext;
use crate::ledger::{
    access, accounts, categorize, display_prefs, holdings, manual_ledger, members, snapshots,
    transactions, valuation,
};

static COLOR: LazyLock<regex::Regex> =
    LazyLock::new(|| regex::Regex::new("^#[0-9A-Fa-f]{6}$").expect("regex"));
static LOGO_KEY: LazyLock<regex::Regex> =
    LazyLock::new(|| regex::Regex::new("^[a-z0-9-]{1,32}$").expect("regex"));
/// java.util.Currency.getAvailableCurrencies() of the JDK 21 the Java backend runs on.
static CURRENCIES: LazyLock<HashSet<&'static str>> = LazyLock::new(|| {
    "ADP AED AFA AFN ALL AMD ANG AOA ARS ATS AUD AWG AYM AZM AZN BAM BBD BDT BEF BGL BGN BHD BIF BMD BND BOB BOV BRL BSD BTN BWP BYB BYN BYR BZD CAD CDF CHE CHF CHW CLF CLP CNY COP COU CRC CSD CUC CUP CVE CYP CZK DEM DJF DKK DOP DZD EEK EGP ERN ESP ETB EUR FIM FJD FKP FRF GBP GEL GHC GHS GIP GMD GNF GRD GTQ GWP GYD HKD HNL HRK HTG HUF IDR IEP ILS INR IQD IRR ISK ITL JMD JOD JPY KES KGS KHR KMF KPW KRW KWD KYD KZT LAK LBP LKR LRD LSL LTL LUF LVL LYD MAD MDL MGA MGF MKD MMK MNT MOP MRO MRU MTL MUR MVR MWK MXN MXV MYR MZM MZN NAD NGN NIO NLG NOK NPR NZD OMR PAB PEN PGK PHP PKR PLN PTE PYG QAR ROL RON RSD RUB RUR RWF SAR SBD SCR SDD SDG SEK SGD SHP SIT SKK SLE SLL SOS SRD SRG SSP STD STN SVC SYP SZL THB TJS TMM TMT TND TOP TPE TRL TRY TTD TWD TZS UAH UGX USD USN USS UYI UYU UZS VEB VED VEF VES VND VUV WST XAF XAG XAU XBA XBB XBC XBD XCD XCG XDR XFO XFU XOF XPD XPF XPT XSU XTS XUA XXX YER YUM ZAR ZMK ZMW ZWD ZWL ZWN ZWR".split(' ').collect()
});

/// The AccountRequest body.
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AccountRequest {
    pub name: Option<String>,
    #[serde(rename = "type")]
    pub account_type: Option<AccountType>,
    pub provider: Option<String>,
    pub currency: Option<String>,
    pub current_balance: Option<Decimal>,
    #[serde(default, deserialize_with = "null_as_false")]
    pub is_manual: bool,
    pub color: Option<String>,
    pub ticker: Option<String>,
    pub logo_key: Option<String>,
    pub institution_id: Option<String>,
    pub opened_at: Option<NaiveDate>,
}

impl ValidateAt for AccountRequest {
    fn validate_at(&self, check: &mut Check<'_>) {
        check
            .errors
            .not_blank("name", self.name.as_deref())
            .size("name", self.name.as_deref(), 0, 100)
            .not_null("type", self.account_type.as_ref())
            .size("provider", self.provider.as_deref(), 0, 100)
            .not_blank("currency", self.currency.as_deref())
            .size("currency", self.currency.as_deref(), 0, 10)
            .pattern(
                "color",
                self.color.as_deref(),
                &COLOR,
                "Color must be a valid hex color",
            )
            .size("ticker", self.ticker.as_deref(), 0, 20)
            .pattern(
                "logoKey",
                self.logo_key.as_deref(),
                &LOGO_KEY,
                "Logo key must be a lowercase slug",
            )
            .size("institutionId", self.institution_id.as_deref(), 0, 200);
        if let Some(currency) = self.currency.as_deref()
            && !currency.trim().is_empty()
            && !CURRENCIES.contains(currency)
        {
            check.errors.add(
                "currency",
                "Currency must be a valid ISO 4217 code (e.g., EUR, USD, GBP)",
            );
        }
        check
            .decimal_min("currentBalance", self.current_balance, "0", true)
            .past_or_present("openedAt", self.opened_at);
    }
}

impl AccountRequest {
    fn name(&self) -> &str {
        self.name.as_deref().unwrap_or_default()
    }

    fn kind(&self) -> AccountType {
        self.account_type.unwrap_or(AccountType::Other)
    }
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct VisibilityRequest {
    #[serde(default, deserialize_with = "null_as_false")]
    hidden: bool,
}

impl Validate for VisibilityRequest {
    fn validate(&self, _errors: &mut Errors) {}
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DisplayRequest {
    #[serde(default, deserialize_with = "null_as_false")]
    collapsed: bool,
}

impl Validate for DisplayRequest {
    fn validate(&self, _errors: &mut Errors) {}
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DisplayOrderRequest {
    account_ids: Option<Vec<Option<i64>>>,
}

impl Validate for DisplayOrderRequest {
    fn validate(&self, errors: &mut Errors) {
        errors.not_null("accountIds", self.account_ids.as_ref());
    }
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SnapshotRequest {
    balance: Option<Decimal>,
    date: Option<NaiveDate>,
}

impl ValidateAt for SnapshotRequest {
    fn validate_at(&self, check: &mut Check<'_>) {
        check
            .errors
            .not_null("balance", self.balance.as_ref())
            .not_null("date", self.date.as_ref());
        check.decimal_min("balance", self.balance, "0", true);
    }
}

/// A balance_snapshot row as Jackson writes the entity.
#[serde_with::skip_serializing_none]
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SnapshotResponse {
    pub id: i64,
    pub date: NaiveDate,
    #[serde(with = "jdecimal")]
    pub balance: Decimal,
    #[serde(with = "jdecimal")]
    pub invested_amount: Decimal,
    pub created_at: DateTime<Utc>,
    pub source: SnapshotSource,
    #[serde(with = "jdecimal::option")]
    pub native_balance: Option<Decimal>,
    pub statement_entered_at: Option<DateTime<Utc>>,
}

impl From<BalanceSnapshot> for SnapshotResponse {
    fn from(s: BalanceSnapshot) -> Self {
        Self {
            id: s.id,
            date: s.date,
            balance: s.balance,
            invested_amount: s.invested_amount,
            created_at: s.created_at,
            source: s.source,
            native_balance: s.native_balance,
            statement_entered_at: s.statement_entered_at,
        }
    }
}

/// signedBalance: a card's amount owed is stored negative.
pub fn signed_balance(kind: AccountType, balance: Decimal) -> Decimal {
    if kind == AccountType::CreditCard {
        -balance.abs()
    } else {
        balance
    }
}

/// normalizeLogoKey: a logo key only ever means something on a wallet that already has one.
fn normalize_logo_key(
    requested: Option<&str>,
    stored: Option<&str>,
    kind: AccountType,
) -> Option<String> {
    let stored = stored?;
    if kind != AccountType::Crypto {
        return None;
    }
    Some(requested.unwrap_or(stored).to_owned())
}

/// BankConnectorPort.DEFAULT_COUNTRY.
const DEFAULT_COUNTRY: &str = "FR";

/// BankLogoResolver.countryOf: the second segment of "name::country[::psu]".
fn country_of(institution_id: Option<&str>) -> Option<String> {
    let (_, rest) = institution_id?.split_once("::")?;
    let country = rest.split_once("::").map_or(rest, |(country, _)| country);
    (!country.trim().is_empty()).then(|| country.to_owned())
}

async fn bank_logo_url(
    state: &AppState,
    provider: Option<&str>,
    institution_id: Option<&str>,
) -> Option<String> {
    let provider = provider.filter(|p| !p.trim().is_empty())?;
    let country = country_of(institution_id).unwrap_or_else(|| DEFAULT_COUNTRY.to_owned());
    state
        .accounts
        .bank_logos
        .logo_url(&country, institution_id, provider)
        .await
}

fn snapshot_ctx(state: &AppState) -> SnapshotContext<'_> {
    SnapshotContext {
        prices: &*state.ledger.prices,
        today: state.clock.today(),
        now: state.clock.now(),
    }
}

async fn prefs_by_account(
    conn: &mut DbHandle<'_>,
    member_id: i64,
) -> AppResult<HashMap<i64, AccountDisplayPref>> {
    Ok(display_prefs::find_by_member(&mut *conn, member_id)
        .await?
        .into_iter()
        .map(|p| (p.account_id, p))
        .collect())
}

/// findById: a readable account with the viewer's share and display prefs.
pub async fn viewer_response(
    state: &AppState,
    conn: &mut DbHandle<'_>,
    account: &Account,
    member_id: i64,
) -> AppResult<AccountResponse> {
    let share = access::share_for(&mut *conn, account, member_id).await?;
    let pref = display_prefs::find_by_member_and_account(&mut *conn, member_id, account.id).await?;
    Ok(to_response(
        &mut *conn,
        &*state.ledger.prices,
        account,
        Some(share),
        Some(member_id),
        state.clock.today(),
    )
    .await?
    .with_display_prefs(pref.as_ref()))
}

pub async fn find_all(
    State(state): State<AppState>,
    auth: Auth,
    RawQuery(query): RawQuery,
) -> AppResult<axum::Json<Vec<AccountResponse>>> {
    let include_hidden = Params::parse(query.as_deref()).boolean("includeHidden", false)?;
    let mut conn = state.db.pool_handle();
    let mut list = access::readable_accounts(&mut conn, auth.member_id).await?;
    super::scale::fix_accounts(&mut list);
    if !include_hidden {
        list.retain(|a| !a.hidden);
    }
    let shares = access::shares_for(&mut conn, &list, auth.member_id).await?;
    let prefs = prefs_by_account(&mut conn, auth.member_id).await?;
    let out = responses_for_viewer(
        &mut conn,
        &*state.ledger.prices,
        &list,
        &shares,
        &prefs,
        auth.member_id,
        state.clock.today(),
    )
    .await?;
    Ok(axum::Json(out))
}

pub async fn find_by_id(
    State(state): State<AppState>,
    auth: Auth,
    Path(id): Path<i64>,
) -> AppResult<axum::Json<AccountResponse>> {
    let mut conn = state.db.pool_handle();
    let account =
        super::scale::fixed(access::require_readable(&mut conn, id, auth.member_id).await?);
    Ok(axum::Json(
        viewer_response(&state, &mut conn, &account, auth.member_id).await?,
    ))
}

pub async fn create(
    State(state): State<AppState>,
    auth: Auth,
    ValidJson(req): ValidJson<AccountRequest>,
) -> AppResult<(StatusCode, axum::Json<AccountResponse>)> {
    let kind = req.kind();
    let scpi = kind == AccountType::Scpi;
    let peer_loan = kind == AccountType::PeerLoan;
    // A typed balance belongs to a cash account: a SCPI is valued from its position and a peer
    // loan from its repayment ledger.
    let opening = if scpi || peer_loan {
        Decimal::ZERO
    } else {
        signed_balance(kind, req.current_balance.unwrap_or(Decimal::ZERO))
    };
    let logo_url = if req.is_manual {
        bank_logo_url(
            &state,
            req.provider.as_deref(),
            req.institution_id.as_deref(),
        )
        .await
    } else {
        None
    };
    let mut tx = state.db.begin().await?;
    members::find_by_id(&mut tx, auth.member_id)
        .await?
        .ok_or_else(|| AppError::NotFound("Family member not found".to_owned()))?;
    let mut new = accounts::NewAccount::new(auth.member_id, req.name(), kind);
    new.provider = req.provider.clone();
    new.currency = if scpi || peer_loan {
        "EUR".to_owned()
    } else {
        req.currency.clone().unwrap_or_default()
    };
    new.current_balance = opening;
    new.is_manual = scpi || peer_loan || req.is_manual;
    new.color = req.color.clone().unwrap_or_else(|| "#6366f1".to_owned());
    new.ticker = req.ticker.clone();
    new.logo_key = normalize_logo_key(req.logo_key.as_deref(), None, kind);
    new.logo_url = logo_url;
    new.opened_at = req.opened_at;
    let mut account = accounts::insert(&mut tx, &new, state.clock.now()).await?;
    // Java answers with the entity it just saved: the balance keeps the scale it was sent with.
    account.current_balance = opening;

    let ctx = snapshot_ctx(&state);
    if account.current_balance > Decimal::ZERO
        || (account.account_type == AccountType::CreditCard && !account.current_balance.is_zero())
    {
        let invested =
            valuation::invested_amount(&mut tx.as_handle(), ctx.prices, &account, ctx.today)
                .await?;
        let eur = ctx
            .prices
            .to_eur(
                Some(account.current_balance),
                &account.currency,
                account.ticker.as_deref(),
                ctx.today,
            )
            .await;
        snapshots::insert(
            &mut tx,
            &NewSnapshot::derived(account.id, ctx.today, eur, invested),
            ctx.now,
        )
        .await?;
    }
    let response = to_response(
        &mut tx.as_handle(),
        ctx.prices,
        &account,
        None,
        None,
        ctx.today,
    )
    .await?;
    tx.commit().await?;
    Ok((StatusCode::CREATED, axum::Json(response)))
}

pub async fn update(
    State(state): State<AppState>,
    auth: Auth,
    Path(id): Path<i64>,
    ValidJson(req): ValidJson<AccountRequest>,
) -> AppResult<axum::Json<AccountResponse>> {
    let kind = req.kind();
    // Pre-read and resolve any external Enable Banking logo lookup BEFORE opening the write
    // transaction so we neither deadlock in-memory SQLite nor hold the SQLite writer gate across
    // an external HTTP request (matching `create()`).
    let existing =
        super::scale::fixed(accounts::get_or_throw(&state.db, id, auth.member_id).await?);
    if existing.account_type != AccountType::Scpi
        && kind == AccountType::Scpi
        && !holdings::find_by_account(&state.db, existing.id)
            .await?
            .is_empty()
    {
        return Err(AppError::BadRequest(
            "Cannot convert an account that still has holdings to SCPI".to_owned(),
        ));
    }
    let resolved_logo: Option<Option<String>> = if existing.is_manual {
        match req.provider.as_deref().filter(|p| !p.trim().is_empty()) {
            None => Some(None),
            Some(provider) => {
                let changed = existing.provider.as_deref() != Some(provider);
                if changed || existing.logo_url.is_none() {
                    Some(bank_logo_url(&state, Some(provider), req.institution_id.as_deref()).await)
                } else {
                    None
                }
            }
        }
    } else {
        None
    };

    let mut tx = state.db.begin().await?;
    let mut account =
        super::scale::fixed(accounts::get_or_throw(&mut tx, id, auth.member_id).await?);
    let name_changed = req.name() != account.name;
    let previous_type = account.account_type;
    if previous_type != AccountType::Scpi
        && kind == AccountType::Scpi
        && !holdings::find_by_account(&mut tx, account.id)
            .await?
            .is_empty()
    {
        return Err(AppError::BadRequest(
            "Cannot convert an account that still has holdings to SCPI".to_owned(),
        ));
    }
    account.name = req.name().to_owned();
    account.account_type = kind;
    account.provider = req.provider.clone();
    account.currency = req.currency.clone().unwrap_or_default();
    // refreshBankLogo: manual accounts only, and only when the bank changed or never resolved.
    if account.is_manual {
        match account.provider.as_deref().filter(|p| !p.trim().is_empty()) {
            None => account.logo_url = None,
            Some(_) => {
                if let Some(logo_url) = resolved_logo {
                    account.logo_url = logo_url;
                }
            }
        }
    }
    if let Some(color) = &req.color {
        account.color = color.clone();
    }
    account.ticker = req.ticker.clone();
    account.logo_key = normalize_logo_key(
        req.logo_key.as_deref(),
        account.logo_key.as_deref(),
        account.account_type,
    );
    if req.opened_at.is_some() {
        account.opened_at = req.opened_at;
    }
    match account.account_type {
        AccountType::Scpi => {
            account.is_manual = true;
            account.currency = "EUR".to_owned();
        }
        AccountType::PeerLoan => {
            account.is_manual = true;
            account.currency = "EUR".to_owned();
            if previous_type != AccountType::PeerLoan {
                account.current_balance = Decimal::ZERO;
            }
        }
        _ => {}
    }

    let ctx = snapshot_ctx(&state);
    if previous_type != AccountType::Scpi && account.account_type == AccountType::Scpi {
        account.current_balance = Decimal::ZERO;
    } else if account.is_manual
        && let Some(typed) = req.current_balance
        && account.account_type != AccountType::Scpi
        && account.account_type != AccountType::PeerLoan
    {
        let old = account.current_balance;
        let new_balance = signed_balance(account.account_type, typed);
        account.current_balance = new_balance;
        // Only a figure that changed is a statement: the form resends the balance it opened with.
        if new_balance != old {
            let snap = valuation::upsert_snapshot_from_native(
                &mut tx.as_handle(),
                &ctx,
                &account,
                new_balance,
                ctx.today,
            )
            .await?;
            if account.account_type.is_ledger() {
                // A typed balance on a manual ledger is a statement about today (issue #543).
                mark_manual(&mut tx.as_handle(), snap.id, new_balance, ctx.now).await?;
            }
        }
    }

    let saved = accounts::update(&mut tx, &account, ctx.now).await?;
    account.updated_at = saved.updated_at;

    // A renamed Revolut pocket carries its new name onto its transactions and the wallet-side legs.
    if name_changed
        && let (Some(parent), Some(external)) = (
            account.parent_account_id,
            account.external_account_id.as_deref(),
        )
    {
        db::query("UPDATE \"transaction\" SET merchant_label = $2 WHERE account_id = $1")
            .bind(account.id)
            .bind(&account.name)
            .execute(&mut tx)
            .await?;
        update_pocket_wallet_side(&mut tx, parent, external, &account.name).await?;
    }
    tx.commit().await?;
    let response = to_response(
        &mut state.db.pool_handle(),
        ctx.prices,
        &account,
        None,
        None,
        ctx.today,
    )
    .await?;
    Ok(axum::Json(response))
}

async fn mark_manual(
    conn: &mut DbHandle<'_>,
    snapshot_id: i64,
    native: Decimal,
    now: DateTime<Utc>,
) -> sqlx::Result<BalanceSnapshot> {
    db::query("UPDATE balance_snapshot SET source = 'MANUAL' WHERE id = $1")
        .bind(snapshot_id)
        .execute(&mut *conn)
        .await?;
    snapshots::set_native_balance(&mut *conn, snapshot_id, native, now).await
}

/// TransactionRepository.updateMerchantLabelForPocketWalletSide.
const POCKET_WALLET_SIDE_SQL: &str = "UPDATE \"transaction\" SET merchant_label = $3 WHERE account_id = $1 \
    AND LOWER(description) LIKE LOWER('%mb:' || $2)";

async fn update_pocket_wallet_side(
    db: impl DbExec<'_>,
    parent_id: i64,
    pocket_external_id: &str,
    name: &str,
) -> sqlx::Result<()> {
    db::query(POCKET_WALLET_SIDE_SQL)
        .bind(parent_id)
        .bind(pocket_external_id)
        .bind(name)
        .execute(db)
        .await?;
    Ok(())
}

pub async fn set_hidden(
    State(state): State<AppState>,
    auth: Auth,
    Path(id): Path<i64>,
    Json(req): Json<VisibilityRequest>,
) -> AppResult<axum::Json<AccountResponse>> {
    let mut tx = state.db.begin().await?;
    let mut account =
        super::scale::fixed(accounts::get_or_throw(&mut tx, id, auth.member_id).await?);
    account.hidden = req.hidden;
    let saved = accounts::update(&mut tx, &account, state.clock.now()).await?;
    account.updated_at = saved.updated_at;
    tx.commit().await?;
    let response = to_response(
        &mut state.db.pool_handle(),
        &*state.ledger.prices,
        &account,
        None,
        None,
        state.clock.today(),
    )
    .await?;
    Ok(axum::Json(response))
}

pub async fn set_collapsed(
    State(state): State<AppState>,
    auth: Auth,
    Path(id): Path<i64>,
    Json(req): Json<DisplayRequest>,
) -> AppResult<axum::Json<AccountResponse>> {
    let mut tx = state.db.begin().await?;
    let account = super::scale::fixed(
        access::require_readable(&mut tx.as_handle(), id, auth.member_id).await?,
    );
    display_prefs::upsert(&mut tx, id, auth.member_id, None, Some(req.collapsed)).await?;
    tx.commit().await?;
    let response = viewer_response(
        &state,
        &mut state.db.pool_handle(),
        &account,
        auth.member_id,
    )
    .await?;
    Ok(axum::Json(response))
}

pub async fn set_display_order(
    State(state): State<AppState>,
    auth: Auth,
    Json(req): Json<DisplayOrderRequest>,
) -> AppResult<axum::Json<Vec<AccountResponse>>> {
    // A null element binds to a null Long in Java; it can never be a readable account.
    let ids: Vec<Option<i64>> = req.account_ids.unwrap_or_default();
    let distinct: HashSet<Option<i64>> = ids.iter().copied().collect();
    if distinct.len() != ids.len() {
        return Err(AppError::BadRequest(
            "Duplicate account id in display order".to_owned(),
        ));
    }
    let mut tx = state.db.begin().await?;
    if ids.is_empty() {
        display_prefs::clear_positions(&mut tx, auth.member_id).await?;
        tx.commit().await?;
        return Ok(axum::Json(Vec::new()));
    }
    let mut readable = Vec::with_capacity(ids.len());
    for id in &ids {
        let id = id.ok_or_else(accounts::account_not_found)?;
        readable.push(super::scale::fixed(
            access::require_readable(&mut tx.as_handle(), id, auth.member_id).await?,
        ));
    }
    let prefs = prefs_by_account(&mut tx.as_handle(), auth.member_id).await?;
    let shares = access::shares_for(&mut tx, &readable, auth.member_id).await?;
    for (position, account) in readable.iter().enumerate() {
        let position = i32::try_from(position).map_err(AppError::internal)?;
        display_prefs::upsert(&mut tx, account.id, auth.member_id, Some(position), None).await?;
    }
    tx.commit().await?;

    let mut conn = state.db.pool_handle();
    let mut out = Vec::with_capacity(readable.len());
    for (position, account) in readable.iter().enumerate() {
        let collapsed = prefs.get(&account.id).is_some_and(|p| p.list_collapsed);
        let position = i32::try_from(position).map_err(AppError::internal)?;
        let mut response = to_response(
            &mut conn,
            &*state.ledger.prices,
            account,
            shares.get(&account.id).copied(),
            Some(auth.member_id),
            state.clock.today(),
        )
        .await?;
        response.list_position = Some(position);
        response.list_collapsed = collapsed;
        out.push(response);
    }
    Ok(axum::Json(out))
}

pub async fn get_history(
    State(state): State<AppState>,
    auth: Auth,
    Path(id): Path<i64>,
    RawQuery(query): RawQuery,
) -> AppResult<axum::Json<Vec<SnapshotResponse>>> {
    let params = Params::parse(query.as_deref());
    let from = params.date("from")?;
    let to = params.date("to")?;
    let mut conn = state.db.pool_handle();
    super::scale::fixed(accounts::get_or_throw(&mut conn, id, auth.member_id).await?);
    let to = to.unwrap_or_else(|| state.clock.today());
    let from = from.unwrap_or_else(|| to.checked_sub_months(Months::new(12)).unwrap_or(to));
    let mut rows = snapshots::find_by_account_between(&mut conn, id, from, to).await?;
    rows.iter_mut().for_each(super::scale::fix_snapshot);
    Ok(axum::Json(
        rows.into_iter().map(SnapshotResponse::from).collect(),
    ))
}

pub async fn add_snapshot(
    State(state): State<AppState>,
    auth: Auth,
    Path(id): Path<i64>,
    ValidJson(req): ValidJson<SnapshotRequest>,
) -> AppResult<(StatusCode, axum::Json<SnapshotResponse>)> {
    let (Some(typed), Some(date)) = (req.balance, req.date) else {
        return Err(AppError::BadRequest("Failed to read request".to_owned()));
    };
    let mut tx = state.db.begin().await?;
    let mut account =
        super::scale::fixed(accounts::get_or_throw(&mut tx, id, auth.member_id).await?);
    if account.account_type == AccountType::PeerLoan {
        return Err(AppError::BadRequest(
            "A peer loan's balance comes from its repayment ledger, not a manual snapshot"
                .to_owned(),
        ));
    }
    let ctx = snapshot_ctx(&state);
    let balance = signed_balance(account.account_type, typed);
    let manual_ledger = account.is_manual && account.account_type.is_ledger();
    if !manual_ledger {
        let latest = snapshots::find_latest_by_account(&mut tx, id).await?;
        if latest.is_none_or(|l| date >= l.date) {
            account.current_balance = balance;
            account.last_synced_at = Some(ctx.now);
            accounts::update(&mut tx, &account, ctx.now).await?;
        }
    }
    // Hand-entered statements are ground truth: rebuilds keep MANUAL rows, with their native figure.
    let snap =
        valuation::upsert_snapshot_from_native(&mut tx.as_handle(), &ctx, &account, balance, date)
            .await?;
    let mut snap = mark_manual(&mut tx.as_handle(), snap.id, balance, ctx.now).await?;
    if manual_ledger {
        account.current_balance = manual_ledger::compute_manual_ledger_balance(
            &mut tx.as_handle(),
            &account,
            ctx.prices,
            ctx.today,
        )
        .await?;
        account.last_synced_at = Some(ctx.now);
        accounts::update(&mut tx, &account, ctx.now).await?;
        manual_ledger::reconstruct_snapshots_from_db(
            &mut tx.as_handle(),
            &account,
            ctx.prices,
            ctx.today,
            ctx.now,
        )
        .await?;
    }
    // Java answers with the entity it saved: the figures it set keep their scale.
    snap.native_balance = Some(balance);
    snap.balance = ctx
        .prices
        .to_eur(
            Some(balance),
            &account.currency,
            account.ticker.as_deref(),
            ctx.today,
        )
        .await;
    tx.commit().await?;
    Ok((StatusCode::CREATED, axum::Json(snap.into())))
}

fn entry_ctx(state: &AppState) -> ManualEntryContext<'_> {
    ManualEntryContext {
        prices: &*state.ledger.prices,
        today: state.clock.today(),
        now: state.clock.now(),
    }
}

pub async fn get_transactions(
    State(state): State<AppState>,
    auth: Auth,
    Path(id): Path<i64>,
) -> AppResult<axum::Json<Vec<TransactionResponse>>> {
    let mut conn = state.db.pool_handle();
    let account = super::scale::fixed(accounts::get_or_throw(&mut conn, id, auth.member_id).await?);
    let rows = transactions::find_by_account_newest_first(&mut conn, id).await?;
    let ids: Vec<i64> = rows.iter().map(|t| t.id).collect();
    let links = peer_loan_links(&mut conn, &ids).await?;
    let names = category_names(&mut conn, &rows).await?;
    Ok(axum::Json(
        rows.iter()
            .map(|t| {
                TransactionResponse::from_row(
                    t,
                    &account.name,
                    t.category_id.and_then(|c| names.get(&c).cloned()),
                    links.get(&t.id).cloned(),
                )
            })
            .collect(),
    ))
}

async fn category_names(
    db: impl DbExec<'_>,
    rows: &[crate::ledger::model::Transaction],
) -> sqlx::Result<HashMap<i64, String>> {
    let ids: Vec<i64> = rows.iter().filter_map(|t| t.category_id).collect();
    if ids.is_empty() {
        return Ok(HashMap::new());
    }
    let pairs: Vec<(i64, String)> =
        db::query_as("SELECT id, name FROM category WHERE id = ANY($1)")
            .bind(&ids)
            .fetch_all(db)
            .await?;
    Ok(pairs.into_iter().collect())
}

/// PeerLoanTransactionLinks.linksFor: the lending outflow of a loan, then repayments.
async fn peer_loan_links(
    conn: &mut DbHandle<'_>,
    ids: &[i64],
) -> sqlx::Result<HashMap<i64, PeerLoanLink>> {
    let mut links = HashMap::new();
    if ids.is_empty() {
        return Ok(links);
    }
    let lending: Vec<(i64, i64, String)> = db::query_as(
        "SELECT t.lending_transaction_id, a.id, a.name FROM peer_loan_terms t \
         JOIN account a ON a.id = t.account_id AND a.deleted_at IS NULL \
         WHERE t.lending_transaction_id = ANY($1) ORDER BY t.id",
    )
    .bind(ids)
    .fetch_all(&mut *conn)
    .await?;
    for (tx_id, account_id, name) in lending {
        links.entry(tx_id).or_insert(PeerLoanLink {
            kind: PeerLoanLinkKind::Lending,
            account_id: Some(account_id),
            account_name: Some(name),
        });
    }
    let repayments: Vec<(i64, i64, String)> = db::query_as(
        "SELECT r.cash_transaction_id, a.id, a.name FROM peer_loan_repayment r \
         JOIN account a ON a.id = r.account_id AND a.deleted_at IS NULL \
         WHERE r.cash_transaction_id = ANY($1) ORDER BY r.id",
    )
    .bind(ids)
    .fetch_all(&mut *conn)
    .await?;
    for (tx_id, account_id, name) in repayments {
        links.entry(tx_id).or_insert(PeerLoanLink {
            kind: PeerLoanLinkKind::Repayment,
            account_id: Some(account_id),
            account_name: Some(name),
        });
    }
    Ok(links)
}

/// Resolves an ISIN ticker via OpenFIGI outside any database transaction so SQLite's global
/// `writer_gate` is never held across an external HTTP request.
async fn pre_resolve_isin(
    state: &AppState,
    account_id: i64,
    member_id: i64,
    tx_id: Option<i64>,
    req: &TransactionRequest,
) -> AppResult<Option<crate::ledger::instrument::TickerResult>> {
    let Some(raw) = req.ticker.as_deref() else {
        return Ok(None);
    };
    if !crate::ledger::instrument::is_isin(raw) || req.fees.is_some_and(|f| f < Decimal::ZERO) {
        return Ok(None);
    }
    let Some(account) = accounts::find_by_id_and_member(&state.db, account_id, member_id).await?
    else {
        return Ok(None);
    };
    if account.account_type == AccountType::PreciousMetals {
        return Ok(None);
    }
    if let Some(existing_tx_id) = tx_id {
        let is_manual = transactions::find_by_id_and_account(&state.db, existing_tx_id, account.id)
            .await?
            .is_some_and(|t| t.is_manual);
        if !is_manual {
            return Ok(None);
        }
    }
    Ok(Some(state.accounts.isin.resolve(raw).await))
}

pub async fn add_transaction(
    State(state): State<AppState>,
    auth: Auth,
    Path(id): Path<i64>,
    Json(req): Json<TransactionRequest>,
) -> AppResult<(StatusCode, axum::Json<TransactionResponse>)> {
    let kb = state.ledger.knowledge_base(&state.db).await?;
    let categorization =
        categorize::load_context(&state.db, kb, auth.member_id, state.clock.now()).await?;
    let preresolved = pre_resolve_isin(&state, id, auth.member_id, None, &req).await?;
    let mut tx = state.db.begin().await?;
    let response = manual_transactions::add_transaction(
        &mut tx.as_handle(),
        entry_ctx(&state),
        &categorization,
        id,
        auth.member_id,
        &req,
        async move |isin: &str| {
            preresolved.unwrap_or_else(|| crate::ledger::instrument::TickerResult {
                ticker: crate::ledger::sync_values::java_trim(isin).to_uppercase(),
                name: None,
            })
        },
    )
    .await?;
    tx.commit().await?;
    Ok((StatusCode::CREATED, axum::Json(response)))
}

pub async fn update_transaction(
    State(state): State<AppState>,
    auth: Auth,
    Path((id, tx_id)): Path<(i64, i64)>,
    Json(req): Json<TransactionRequest>,
) -> AppResult<axum::Json<TransactionResponse>> {
    let preresolved = pre_resolve_isin(&state, id, auth.member_id, Some(tx_id), &req).await?;
    let mut tx = state.db.begin().await?;
    let response = manual_transactions::update_transaction(
        &mut tx.as_handle(),
        entry_ctx(&state),
        id,
        tx_id,
        auth.member_id,
        &req,
        async move |isin: &str| {
            preresolved.unwrap_or_else(|| crate::ledger::instrument::TickerResult {
                ticker: crate::ledger::sync_values::java_trim(isin).to_uppercase(),
                name: None,
            })
        },
    )
    .await?;
    tx.commit().await?;
    Ok(axum::Json(response))
}

pub async fn delete_transaction(
    State(state): State<AppState>,
    auth: Auth,
    Path((id, tx_id)): Path<(i64, i64)>,
) -> AppResult<StatusCode> {
    let mut tx = state.db.begin().await?;
    manual_transactions::delete_transaction(
        &mut tx.as_handle(),
        entry_ctx(&state),
        id,
        tx_id,
        auth.member_id,
    )
    .await?;
    tx.commit().await?;
    Ok(StatusCode::NO_CONTENT)
}
