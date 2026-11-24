//! EnableBankingBankConnector: the Enable Banking API, authenticated by a short-lived RS256 JWT.
//! https://enablebanking.com/docs/api/reference/

use std::time::Duration;

use chrono::NaiveDate;
use jsonwebtoken::{Algorithm, EncodingKey, Header};
use percent_encoding::{AsciiSet, NON_ALPHANUMERIC, utf8_percent_encode};
use reqwest::{Method, StatusCode};
use rsa::pkcs1::EncodeRsaPrivateKey;
use serde::de::DeserializeOwned;
use serde::{Deserialize, Serialize};

use super::balances::{self, AccountData, AccountDetails, AccountFetch, BalancesResponse, Scope};
use super::entries::{self, TransactionsResponse};
use super::error::EbError;
use super::wire::{fingerprint, java_split, safe};
use crate::AppState;
use crate::ledger::bank_import::TransactionData;
use crate::native_sidecars::common::{ReadBodyError, read_capped_text};

const TIMEOUT: Duration = Duration::from_secs(30);
const MAX_RESPONSE_BYTES: usize = 8 * 1024 * 1024;
/// Bank coverage per country changes rarely; this avoids re-asking on every "Add Account" open.
const COUNTRIES_CACHE_TTL_SECONDS: i64 = 21_600;
/// Bounds fetch_transactions: a few ASPSPs paginate very small.
const MAX_TRANSACTION_PAGES: usize = 20;
/// Raised from 20 when the psu_type filter was dropped: the logo lookup re-searches by full name.
const MAX_INSTITUTION_RESULTS: usize = 30;
pub const DEFAULT_COUNTRY: &str = "FR";
const PSU_PERSONAL: &str = "personal";
const PSU_BUSINESS: &str = "business";
const SESSION_ATTEMPTS: u32 = 3;
const SESSION_RETRY_DELAY: Duration = Duration::from_millis(1_500);
const PATH_SEGMENT: &AsciiSet = &NON_ALPHANUMERIC
    .remove(b'-')
    .remove(b'.')
    .remove(b'_')
    .remove(b'~');

/// BankConnectorPort.InstitutionData.
#[serde_with::skip_serializing_none]
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct InstitutionData {
    /// Opaque round-trip token "name::country::psuType"; clients pass it back verbatim.
    pub id: String,
    pub name: Option<String>,
    pub bic: Option<String>,
    pub logo_url: Option<String>,
    pub country: Option<String>,
    pub psu_type: String,
}

/// BankConnectorPort.InitiateResult.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct InitiateResult {
    pub requisition_id: Option<String>,
    pub auth_link: String,
}

fn missing(field: &str) -> EbError {
    EbError::not_configured(format!(
        "Enable Banking is not fully configured: {field} is missing. Open Settings → Integrations → Enable Banking in the app, or re-run the setup wizard. Free registration: https://enablebanking.com/"
    ))
}

#[derive(Serialize)]
struct Claims<'a> {
    iss: &'a str,
    aud: &'a str,
    iat: i64,
    exp: i64,
}

/// buildJwt: kid and issuer from the settings, valid one hour.
async fn build_jwt(state: &AppState) -> Result<String, EbError> {
    let config = &state.sync.enable_banking.config;
    let key_id = config
        .key_id(state)
        .await?
        .ok_or_else(|| missing("Key ID"))?;
    let application_id = config
        .application_id(state)
        .await?
        .ok_or_else(|| missing("Application ID"))?;
    let key = config.private_key(state)?.ok_or_else(|| {
        EbError::not_configured(
            "Enable Banking private key is missing on the server. Open Settings → Integrations → Enable Banking and generate or import the key pair (the Application ID, Key ID and redirect URL are not enough — a private key is also required).",
        )
    })?;
    let der = key
        .to_pkcs1_der()
        .map_err(|e| EbError::Other(crate::error::AppError::internal(e)))?;
    let encoding = EncodingKey::from_rsa_der(der.as_bytes());
    let mut header = Header::new(Algorithm::RS256);
    header.typ = None;
    header.kid = Some(key_id);
    let now = state.clock.now().timestamp();
    let claims = Claims {
        iss: &application_id,
        aud: "api.enablebanking.com",
        iat: now,
        exp: now + 3600,
    };
    jsonwebtoken::encode(&header, &claims, &encoding)
        .map_err(|e| EbError::Other(crate::error::AppError::internal(e)))
}

/// Spring's HttpStatusCode.toString: "503 SERVICE_UNAVAILABLE".
fn status_name(status: StatusCode) -> String {
    match status.canonical_reason() {
        Some(reason) => format!(
            "{} {}",
            status.as_u16(),
            reason.to_ascii_uppercase().replace([' ', '-'], "_")
        ),
        None => status.as_u16().to_string(),
    }
}

/// How a failed call is reported: "context: body" (mapToSyncException) or, for the catalog
/// reads, "context: [status] body".
#[derive(Clone, Copy)]
enum Report {
    Body,
    StatusAndBody,
}

/// One authenticated call. The JWT is built first, so a configuration gap surfaces as itself
/// rather than as a failed call. An empty body is None, like an empty Mono.
async fn call<T: DeserializeOwned>(
    state: &AppState,
    method: Method,
    path: &str,
    query: &[(&str, String)],
    body: Option<serde_json::Value>,
    context: &str,
    report: Report,
) -> Result<Option<T>, EbError> {
    let jwt = build_jwt(state).await?;
    let url = format!(
        "{}{path}",
        state.config.enablebanking.base_url.trim_end_matches('/')
    );
    let mut request = state
        .http
        .request(method, url)
        .header(reqwest::header::ACCEPT, "application/json")
        .bearer_auth(jwt)
        .timeout(TIMEOUT);
    if !query.is_empty() {
        request = request.query(query);
    }
    if let Some(body) = body {
        request = request.json(&body);
    }
    let failed = |message: String| EbError::sync(format!("{context}: {message}"));
    let response = request.send().await.map_err(|e| failed(e.to_string()))?;
    let status = response.status();
    let text = read_capped_text(response, MAX_RESPONSE_BYTES)
        .await
        .map_err(|e| match e {
            ReadBodyError::Transport(err) => failed(err.to_string()),
            ReadBodyError::TooLarge { limit } => {
                failed(format!("response body exceeds {limit} bytes"))
            }
        })?;
    if status.is_client_error() || status.is_server_error() {
        return Err(failed(match report {
            Report::Body => text,
            Report::StatusAndBody => format!("[{}] {text}", status_name(status)),
        }));
    }
    if text.trim().is_empty() {
        return Ok(None);
    }
    serde_json::from_str::<Option<T>>(&text).map_err(|e| failed(e.to_string()))
}

fn segment(value: &str) -> String {
    utf8_percent_encode(value, PATH_SEGMENT).to_string()
}

/// The parts of a composite institution id, the legacy two-segment form included.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct InstitutionRef {
    pub bank_name: String,
    pub country: String,
    pub psu_type: String,
}

/// parseInstitutionId: a missing country is FR, a missing or unexpected PSU type personal (the
/// id is client-supplied and its PSU segment goes into the outbound request).
pub fn parse_institution_id(institution_id: &str) -> Result<InstitutionRef, EbError> {
    let parts = java_split(institution_id);
    let Some(name) = parts.first() else {
        return Err(EbError::Other(crate::error::AppError::internal(
            "institution id has no name segment",
        )));
    };
    let segment = |i: usize, default: &str| {
        parts
            .get(i)
            .filter(|p| !crate::ledger::sync_values::java_is_blank(p))
            .map_or(default.to_owned(), |p| (*p).to_owned())
    };
    let mut psu_type = segment(2, PSU_PERSONAL);
    if psu_type != PSU_PERSONAL && psu_type != PSU_BUSINESS {
        psu_type = PSU_PERSONAL.to_owned();
    }
    Ok(InstitutionRef {
        bank_name: (*name).to_owned(),
        country: segment(1, DEFAULT_COUNTRY),
        psu_type,
    })
}

/// BankConnectorPort.parseInstitutionId's country: the text between the first and second "::",
/// None when blank.
pub fn country_of(institution_id: &str) -> Option<String> {
    let (_, rest) = institution_id.split_once("::")?;
    let country = rest.split_once("::").map_or(rest, |(c, _)| c);
    (!crate::ledger::sync_values::java_is_blank(country)).then(|| country.to_owned())
}

#[derive(Serialize)]
struct AuthBody<'a> {
    access: Access,
    aspsp: Aspsp<'a>,
    state: &'a str,
    redirect_url: String,
    psu_type: &'a str,
}

#[derive(Serialize)]
struct Access {
    valid_until: String,
}

#[derive(Serialize)]
struct Aspsp<'a> {
    name: &'a str,
    country: &'a str,
}

#[derive(Deserialize)]
struct AuthStartResponse {
    url: Option<String>,
    authorization_id: Option<String>,
}

/// POST /auth: the bank's authorization link, with the state nonce the callback echoes back.
pub async fn initiate(
    state: &AppState,
    institution_id: &str,
    oauth_state: &str,
) -> Result<InitiateResult, EbError> {
    let institution = parse_institution_id(institution_id)?;
    let redirect_url = state
        .sync
        .enable_banking
        .config
        .redirect_uri(state)
        .await?
        .ok_or_else(|| missing("redirect URL"))?;
    let valid_until = (state.clock.now() + chrono::Duration::days(90))
        .to_rfc3339_opts(chrono::SecondsFormat::AutoSi, true);
    let body = AuthBody {
        access: Access { valid_until },
        aspsp: Aspsp {
            name: &institution.bank_name,
            country: &institution.country,
        },
        state: oauth_state,
        redirect_url,
        // Must match the ASPSP's own psu_types, or the bank presents the wrong login page.
        psu_type: &institution.psu_type,
    };
    let body = serde_json::to_value(body)
        .map_err(|e| EbError::Other(crate::error::AppError::internal(e)))?;
    let auth: Option<AuthStartResponse> = call(
        state,
        Method::POST,
        "/auth",
        &[],
        Some(body),
        "Enable Banking auth failed",
        Report::Body,
    )
    .await?;
    let Some(auth) = auth else {
        return Err(EbError::sync("Empty response from Enable Banking /auth"));
    };
    let Some(url) = auth.url else {
        return Err(EbError::sync("Empty response from Enable Banking /auth"));
    };
    tracing::info!("Enable Banking auth initiated");
    Ok(InitiateResult {
        requisition_id: auth.authorization_id,
        auth_link: url,
    })
}

#[derive(Deserialize)]
struct SessionCreateResponse {
    session_id: Option<String>,
}

/// POST /sessions: the single-use OAuth code for a session id.
pub async fn exchange_code(state: &AppState, code: &str) -> Result<String, EbError> {
    tracing::info!("Exchanging OAuth code for Enable Banking session");
    let session: Option<SessionCreateResponse> = call(
        state,
        Method::POST,
        "/sessions",
        &[],
        Some(serde_json::json!({ "code": code })),
        "Enable Banking code exchange failed",
        Report::Body,
    )
    .await?;
    let Some(session_id) = session.and_then(|s| s.session_id) else {
        return Err(EbError::sync(
            "Empty session response from Enable Banking /sessions",
        ));
    };
    tracing::info!(
        "Enable Banking session created: {}",
        fingerprint(Some(&session_id))
    );
    Ok(session_id)
}

#[derive(Deserialize)]
struct SessionResponse {
    status: Option<String>,
    accounts: Option<Vec<String>>,
    aspsp: Option<SessionAspsp>,
}

#[derive(Deserialize)]
struct SessionAspsp {
    name: Option<String>,
}

/// GET /sessions/{id} until accounts are linked (Enable Banking links them asynchronously), for
/// about 4.5 s at most: a session still empty after that is returned empty so the caller keeps
/// the requisition retryable.
async fn session_accounts(
    state: &AppState,
    session_id: &str,
) -> Result<(Vec<String>, Scope), EbError> {
    for attempt in 1..=SESSION_ATTEMPTS {
        let session: Option<SessionResponse> = call(
            state,
            Method::GET,
            &format!("/sessions/{}", segment(session_id)),
            &[],
            None,
            "Failed to fetch session",
            Report::Body,
        )
        .await?;
        let status = session.as_ref().and_then(|s| s.status.clone());
        if let Some(session) = session
            && let Some(accounts) = session.accounts.filter(|a| !a.is_empty())
        {
            tracing::info!(
                "Session {} has {} accounts (attempt {attempt}, status={})",
                fingerprint(Some(session_id)),
                accounts.len(),
                status.as_deref().unwrap_or("null")
            );
            let scope = Scope::of_aspsp(session.aspsp.as_ref().and_then(|a| a.name.as_deref()));
            return Ok((accounts, scope));
        }
        tracing::info!(
            "Session {} has no accounts yet (attempt {attempt}/{SESSION_ATTEMPTS}, status={})",
            fingerprint(Some(session_id)),
            status.as_deref().unwrap_or("null")
        );
        if attempt < SESSION_ATTEMPTS {
            tokio::time::sleep(SESSION_RETRY_DELAY).await;
        }
    }
    tracing::warn!(
        "Session {} still has no accounts after {SESSION_ATTEMPTS} attempts — returning empty so the caller can retry asynchronously",
        fingerprint(Some(session_id))
    );
    Ok((Vec::new(), Scope::Unknown))
}

struct Http<'a> {
    state: &'a AppState,
}

impl AccountFetch for Http<'_> {
    async fn details(&self, uid: &str) -> Result<Option<AccountDetails>, EbError> {
        call(
            self.state,
            Method::GET,
            &format!("/accounts/{}/details", segment(uid)),
            &[],
            None,
            "Failed to fetch account details",
            Report::Body,
        )
        .await
    }

    async fn balances(&self, uid: &str) -> Result<Option<BalancesResponse>, EbError> {
        call(
            self.state,
            Method::GET,
            &format!("/accounts/{}/balances", segment(uid)),
            &[],
            None,
            "Failed to fetch account balances",
            Report::Body,
        )
        .await
    }
}

/// The balance of every account linked to the session. An empty list means "still linking".
pub async fn fetch_balances(
    state: &AppState,
    session_id: &str,
) -> Result<Vec<AccountData>, EbError> {
    let (accounts, scope) = session_accounts(state, session_id).await?;
    balances::resolve(&Http { state }, &accounts, scope).await
}

/// GET /accounts/{id}/transactions since date_from, following continuation_key for at most
/// MAX_TRANSACTION_PAGES pages; hitting the cap keeps what was collected.
pub async fn fetch_transactions(
    state: &AppState,
    external_account_id: &str,
    date_from: Option<NaiveDate>,
) -> Result<Vec<TransactionData>, EbError> {
    let mut collected = Vec::new();
    let mut continuation_key: Option<String> = None;
    let path = format!("/accounts/{}/transactions", segment(external_account_id));
    for _ in 0..MAX_TRANSACTION_PAGES {
        let mut query = Vec::new();
        if let Some(from) = date_from {
            query.push(("date_from", from.to_string()));
        }
        if let Some(key) = &continuation_key {
            query.push(("continuation_key", key.clone()));
        }
        let response: Option<TransactionsResponse> = call(
            state,
            Method::GET,
            &path,
            &query,
            None,
            "Failed to fetch account transactions",
            Report::Body,
        )
        .await?;
        let Some(response) = response else { break };
        collected.extend(entries::to_transactions(response.transactions));
        continuation_key = response.continuation_key;
        if continuation_key
            .as_deref()
            .is_none_or(crate::ledger::sync_values::java_is_blank)
        {
            // Never log amounts, descriptions or counterparties (financial PII).
            tracing::info!(
                "Fetched {} transactions for account {} since {}",
                collected.len(),
                fingerprint(Some(external_account_id)),
                date_from.map_or("null".to_owned(), |d| d.to_string())
            );
            return Ok(collected);
        }
    }
    tracing::warn!(
        "Stopped paging transactions for account {} after {MAX_TRANSACTION_PAGES} pages — importing the {} collected so far",
        fingerprint(Some(external_account_id)),
        collected.len()
    );
    Ok(collected)
}

#[derive(Deserialize)]
struct AspspsResponse {
    aspsps: Option<Vec<Option<AspspResponse>>>,
}

#[derive(Debug, Clone, Deserialize)]
pub struct AspspResponse {
    pub name: Option<String>,
    pub bic: Option<String>,
    pub logo: Option<String>,
    pub country: Option<String>,
    pub psu_types: Option<Vec<String>>,
}

/// GET /aspsps, unfiltered by PSU type so business-only banks (Swan, Qonto) are listed too.
pub async fn search_institutions(
    state: &AppState,
    query: Option<&str>,
    country: Option<&str>,
) -> Result<Vec<InstitutionData>, EbError> {
    tracing::info!(
        "Searching institutions: query='{}' country='{}'",
        safe(query.unwrap_or("null")),
        safe(country.unwrap_or("null"))
    );
    let params: Vec<(&str, String)> = country
        .filter(|c| !crate::ledger::sync_values::java_is_blank(c))
        .map(|c| vec![("country", c.to_owned())])
        .unwrap_or_default();
    let response: Option<AspspsResponse> = call(
        state,
        Method::GET,
        "/aspsps",
        &params,
        None,
        "Failed to fetch institutions",
        Report::StatusAndBody,
    )
    .await?;
    let aspsps: Vec<AspspResponse> = response
        .and_then(|r| r.aspsps)
        .unwrap_or_default()
        .into_iter()
        .flatten()
        .collect();
    tracing::info!("Enable Banking returned {} ASPSPs", aspsps.len());
    Ok(to_institutions(&aspsps, query, country))
}

/// Personal whenever the bank offers it, then business, else the bank's own first value.
pub fn resolve_psu_type(psu_types: Option<&[String]>) -> String {
    match psu_types {
        None | Some([]) => PSU_PERSONAL.to_owned(),
        Some(types) if types.iter().any(|t| t == PSU_PERSONAL) => PSU_PERSONAL.to_owned(),
        Some(types) if types.iter().any(|t| t == PSU_BUSINESS) => PSU_BUSINESS.to_owned(),
        Some(types) => types[0].clone(),
    }
}

/// Name filter (case-insensitive), PSU type resolution, then de-duplication by composite id:
/// the same bank can be listed twice with different auth methods.
pub fn to_institutions(
    aspsps: &[AspspResponse],
    query: Option<&str>,
    fallback_country: Option<&str>,
) -> Vec<InstitutionData> {
    let q = query.map(str::to_lowercase).unwrap_or_default();
    let mut by_id: Vec<InstitutionData> = Vec::new();
    for a in aspsps {
        if !q.is_empty()
            && a.name
                .as_deref()
                .is_none_or(|n| !n.to_lowercase().contains(&q))
        {
            continue;
        }
        let country = a.country.as_deref().or(fallback_country);
        let psu_type = resolve_psu_type(a.psu_types.as_deref());
        let id = format!(
            "{}::{}::{psu_type}",
            a.name.as_deref().unwrap_or("null"),
            country.unwrap_or("")
        );
        let candidate = InstitutionData {
            id,
            name: a.name.clone(),
            bic: a.bic.clone(),
            logo_url: a.logo.clone(),
            country: country.map(str::to_owned),
            psu_type,
        };
        match by_id.iter_mut().find(|i| i.id == candidate.id) {
            Some(existing) => {
                if existing.logo_url.is_none() && candidate.logo_url.is_some() {
                    *existing = candidate;
                }
            }
            None => by_id.push(candidate),
        }
        if by_id.len() == MAX_INSTITUTION_RESULTS {
            break;
        }
    }
    by_id
}

#[derive(Deserialize)]
struct ApplicationResponse {
    countries: Option<Vec<String>>,
}

/// GET /application: the countries this application is registered for, sorted, cached six
/// hours. An empty answer is never cached; the last good list is served instead.
pub async fn list_countries(state: &AppState) -> Result<Vec<String>, EbError> {
    let now = state.clock.now();
    let cached = state
        .sync
        .enable_banking
        .countries
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .clone();
    if let Some((countries, at)) = &cached
        && now <= *at + chrono::Duration::seconds(COUNTRIES_CACHE_TTL_SECONDS)
    {
        return Ok(countries.clone());
    }
    let response: Option<ApplicationResponse> = call(
        state,
        Method::GET,
        "/application",
        &[],
        None,
        "Failed to fetch application countries",
        Report::StatusAndBody,
    )
    .await?;
    let countries = match response.and_then(|r| r.countries) {
        Some(mut countries) => {
            countries.sort();
            countries
        }
        None => {
            tracing::warn!(
                "Enable Banking /application returned no countries — not caching this result"
            );
            Vec::new()
        }
    };
    if !countries.is_empty() {
        *state
            .sync
            .enable_banking
            .countries
            .lock()
            .unwrap_or_else(|e| e.into_inner()) = Some((countries.clone(), now));
        return Ok(countries);
    }
    Ok(cached.map_or(countries, |(c, _)| c))
}
