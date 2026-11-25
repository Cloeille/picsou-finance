//! Native Rust port of `services/cmes-auth/` (Crédit Mutuel Épargne Salariale).
//!
//! Endpoints:
//! - `GET /health` -> `{"status": "ok"}`
//! - `POST /initiate` `{login, password, sessionState?, unattended?}`
//! - `POST /complete` `{processId, code?}`
//! - `POST /positions` `{sessionState}` -> `[{externalId, legacyExternalIds, name, planKind, employer, balanceEur, positions, snapshotComplete}]`

use std::collections::{HashMap, HashSet};
use std::sync::LazyLock;
use std::time::Duration;

use async_trait::async_trait;
use chrono::NaiveDate;
use http::{Method, StatusCode};
use regex::Regex;
use rust_decimal::Decimal;
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use sha2::{Digest, Sha256};

use super::common::html_text;
use super::euro_info::{
    APP_VALIDATION_POLL_SECONDS, APP_VALIDATION_TIMEOUT_SECONDS, EuroInfoClient, HttpPage,
    SKIP_PHRASES, Site, TRUST_COOKIE, UpstreamError, find_form_by_button,
};
use super::{NativeSidecar, NativeSidecarError};

pub const DEFAULT_BASE_URL: &str = "https://www.creditmutuel-ere.fr";
pub const LOGIN_PATH: &str = "/fr/identification/authentification.html";
pub const SAVER_SPACE: &str = "/fr/epargnants/espace-client/epargne-salariale";
pub const SITUATION_PATH: &str = "/fr/epargnants/espace-client/epargne-salariale/mon-epargne/situation-financiere-detaillee/index.html";
pub const DASHBOARD_PATH: &str =
    "/fr/epargnants/espace-client/epargne-salariale/tableau-de-bord/index.html";
pub const MY_SAVINGS_PATH_MARKER: &str = "epargne-sur-le-support";

pub const FORMAT_CHANGED: &str = "UPSTREAM_FORMAT_CHANGED";
pub const INCOMPLETE: &str = "PORTFOLIO_INCOMPLETE";

const ACTION_NEEDED_MARKERS: &[&str] = &[
    "premiers-pas/saisir-vos-coordonnees",
    "premiers-pas/vos-services",
    "conditions-generales-d-utilisation",
];
const REPLAYED_COOKIES: &[&str] = &[TRUST_COOKIE];

pub fn default_site(base_url: &str) -> Site {
    Site {
        name: "CMES",
        base_url: base_url.to_owned(),
        login_path: LOGIN_PATH,
        logged_in_marker: "ei_tpl_ident_logout",
        action_needed_path_markers: ACTION_NEEDED_MARKERS,
        skip_phrases: SKIP_PHRASES,
        postpone_button: None,
        default_state_path: None,
        default_cancel_path: None,
        replayed_cookies: Some(REPLAYED_COOKIES),
        unreadable_poll_expires: true,
        app_validation_timeout: Duration::from_secs(APP_VALIDATION_TIMEOUT_SECONDS),
        app_validation_poll: Duration::from_secs(APP_VALIDATION_POLL_SECONDS),
    }
}

pub struct NativeCmes {
    euro: EuroInfoClient,
}

impl Default for NativeCmes {
    fn default() -> Self {
        Self::with_base_url(DEFAULT_BASE_URL)
    }
}

impl NativeCmes {
    pub fn with_base_url(base_url: &str) -> Self {
        Self::with_site(default_site(base_url))
    }

    pub fn with_site(site: Site) -> Self {
        Self {
            euro: EuroInfoClient::new(site),
        }
    }

    async fn fetch_situation(
        &self,
        client: &reqwest::Client,
    ) -> Result<String, NativeSidecarError> {
        self.euro
            .fetch_page(client, SITUATION_PATH, "Situation")
            .await
    }

    fn require_session(&self, page: HttpPage) -> Result<HttpPage, NativeSidecarError> {
        if matches!(
            page.status,
            StatusCode::UNAUTHORIZED | StatusCode::FORBIDDEN
        ) || page.url.path().contains(LOGIN_PATH)
            || EuroInfoClient::is_mfa_page(&page)
        {
            return Err(
                UpstreamError::new("SESSION_EXPIRED", "Session expired").into_sidecar_error()
            );
        }
        Ok(page)
    }

    async fn fetch_fund(
        &self,
        client: &reqwest::Client,
        fund: &SituationFund,
    ) -> Result<PositionPayload, NativeSidecarError> {
        let page = self.fetch_situation(client).await?;
        let Some(form) = find_form_by_button(&page, &fund.button) else {
            return Err(
                UpstreamError::new(FORMAT_CHANGED, "No form holds the fund button")
                    .into_sidecar_error(),
            );
        };
        let mut fields = form.fields;
        if let Some(btn_val) = form.buttons.get(&fund.button) {
            fields.insert(fund.button.clone(), btn_val.clone());
        }
        let situation_url = self.euro.base_url().join(SITUATION_PATH).map_err(|_| {
            NativeSidecarError::new(StatusCode::INTERNAL_SERVER_ERROR, "INTERNAL_ERROR")
        })?;
        let action = if form.action.is_empty() {
            SITUATION_PATH
        } else {
            &form.action
        };
        let target = self
            .euro
            .same_origin(&situation_url, action)
            .map_err(UpstreamError::into_sidecar_error)?;
        let resp = self.euro.post_form(client, target, &fields, None).await?;
        let summary = self.require_session(self.euro.follow(client, resp).await?)?;
        let (isin, fund_code) = parse_fund_identity(&summary.text);

        let savings = if summary.url.path().contains(MY_SAVINGS_PATH_MARKER) {
            summary
        } else {
            let Some(tab) = my_savings_tab(&summary.text) else {
                self.euro.diagnose("fund summary", &summary);
                return Err(UpstreamError::new(
                    FORMAT_CHANGED,
                    "No 'Mon épargne' tab on the fund page",
                )
                .into_sidecar_error());
            };
            let tab_url = self
                .euro
                .same_origin(&summary.url, &tab)
                .map_err(UpstreamError::into_sidecar_error)?;
            let resp = self.euro.get_url(client, tab_url).await?;
            self.require_session(self.euro.follow(client, resp).await?)?
        };

        let details = parse_my_savings(&savings.text).map_err(|e| e.into_sidecar_error())?;
        if !money_close(details.value_eur, fund.value_eur) {
            return Err(
                UpstreamError::new(INCOMPLETE, "Fund page and situation page disagree")
                    .into_sidecar_error(),
            );
        }

        let payload = PositionPayload {
            isin,
            fund_code,
            label: fund.label.clone(),
            quantity: details.quantity,
            unit_value: Some(details.unit_value),
            value_eur: fund.value_eur,
            pnl_eur: Some(details.pnl_eur),
        };
        validate_position(&payload)?;
        Ok(payload)
    }

    async fn collect_plans(
        &self,
        client: &reqwest::Client,
    ) -> Result<Vec<PlanPayload>, NativeSidecarError> {
        let dashboard = self
            .euro
            .fetch_page(client, DASHBOARD_PATH, "Dashboard")
            .await?;
        let employer = parse_employer(&dashboard);
        let page = self.fetch_situation(client).await?;
        let plans = parse_situation(&page).map_err(|e| e.into_sidecar_error())?;

        let external_ids: Vec<String> = plans.iter().map(|p| plan_external_id(&p.name)).collect();
        let unique: HashSet<&str> = external_ids.iter().map(String::as_str).collect();
        if unique.len() != external_ids.len() {
            return Err(
                UpstreamError::new(FORMAT_CHANGED, "Two plans share an external id")
                    .into_sidecar_error(),
            );
        }

        let mut payloads = Vec::with_capacity(plans.len());
        for (plan, external_id) in plans.into_iter().zip(external_ids) {
            let mut positions = Vec::with_capacity(plan.funds.len());
            for fund in &plan.funds {
                positions.push(self.fetch_fund(client, fund).await?);
            }
            let legacy = legacy_external_ids(&plan.name, plan.plan_kind.as_deref());
            let payload = PlanPayload {
                external_id,
                legacy_external_ids: legacy,
                name: plan.name,
                plan_kind: plan.plan_kind,
                employer: employer.clone(),
                balance_eur: plan.balance_eur,
                positions,
                snapshot_complete: true,
            };
            validate_plan(&payload)?;
            payloads.push(payload);
        }
        Ok(payloads)
    }

    async fn handle_positions(&self, body: Value) -> Result<Value, NativeSidecarError> {
        #[derive(Deserialize)]
        #[serde(rename_all = "camelCase", deny_unknown_fields)]
        struct Req {
            session_state: String,
        }
        let req: Req = serde_json::from_value(body)
            .map_err(|_| NativeSidecarError::new(StatusCode::BAD_REQUEST, "INVALID_DATA"))?;
        if req.session_state.len() < 2 || req.session_state.len() > 2_000_000 {
            return Err(NativeSidecarError::new(
                StatusCode::BAD_REQUEST,
                "INVALID_DATA",
            ));
        }

        let (client, jar) = self.euro.new_session_client()?;
        jar.restore_json(&req.session_state, "INVALID_DATA")?;
        let plans = self.collect_plans(&client).await?;
        Ok(serde_json::to_value(plans).expect("serialize CMES plans"))
    }
}

#[async_trait]
impl NativeSidecar for NativeCmes {
    async fn call(
        &self,
        method: Method,
        path: &str,
        body: Value,
    ) -> Result<Value, NativeSidecarError> {
        match (method, path) {
            (Method::GET, "/health") => Ok(json!({ "status": "ok" })),
            (Method::POST, "/initiate") => self.euro.handle_initiate(body).await,
            (Method::POST, "/complete") => self.euro.handle_complete(body).await,
            (Method::POST, "/positions") => self.handle_positions(body).await,
            _ => Err(NativeSidecarError::new(StatusCode::NOT_FOUND, "Not Found")),
        }
    }
}

// ─── Contract payloads & validation ─────────────────────────────────────────

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PositionPayload {
    pub isin: Option<String>,
    pub fund_code: Option<String>,
    pub label: String,
    pub quantity: Decimal,
    pub unit_value: Option<Decimal>,
    pub value_eur: Decimal,
    pub pnl_eur: Option<Decimal>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PlanPayload {
    pub external_id: String,
    pub legacy_external_ids: Vec<String>,
    pub name: String,
    pub plan_kind: Option<String>,
    pub employer: Option<String>,
    pub balance_eur: Decimal,
    pub positions: Vec<PositionPayload>,
    pub snapshot_complete: bool,
}

fn validate_position(p: &PositionPayload) -> Result<(), NativeSidecarError> {
    let invalid = || NativeSidecarError::new(StatusCode::BAD_GATEWAY, "INVALID_DATA");
    if p.label.is_empty() || p.label.chars().count() > 200 {
        return Err(invalid());
    }
    if p.isin.as_ref().is_some_and(|s| s.chars().count() > 12) {
        return Err(invalid());
    }
    if p.fund_code.as_ref().is_some_and(|s| s.chars().count() > 20) {
        return Err(invalid());
    }
    Ok(())
}

fn validate_plan(p: &PlanPayload) -> Result<(), NativeSidecarError> {
    let invalid = || NativeSidecarError::new(StatusCode::BAD_GATEWAY, "INVALID_DATA");
    if p.external_id.is_empty() || p.external_id.chars().count() > 100 {
        return Err(invalid());
    }
    if p.legacy_external_ids
        .iter()
        .any(|id| id.is_empty() || id.chars().count() > 100)
    {
        return Err(invalid());
    }
    if p.name.is_empty() || p.name.chars().count() > 200 {
        return Err(invalid());
    }
    if p.plan_kind.as_ref().is_some_and(|s| s.chars().count() > 30) {
        return Err(invalid());
    }
    if p.employer.as_ref().is_some_and(|s| s.chars().count() > 200) {
        return Err(invalid());
    }
    Ok(())
}

// ─── External IDs ───────────────────────────────────────────────────────────

static SLUG_RE: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"[^a-z0-9]+").expect("static regex"));

fn slug(label: &str) -> String {
    let lower = label.to_lowercase();
    SLUG_RE
        .replace_all(&lower, "-")
        .trim_matches('-')
        .to_owned()
}

/// `cmes_<slug>-<digest>`, from the plan's full label alone. Earlier builds put the
/// saver number after the prefix; the site no longer shows it, and the connector
/// compares ids with that number taken out.
pub fn plan_external_id(label: &str) -> String {
    let hash = Sha256::digest(label.as_bytes());
    let digest = &hex::encode(hash)[..16];
    let s = slug(label);
    let prefix = s.get(..40).unwrap_or(&s).trim_end_matches('-');
    format!("cmes_{prefix}-{digest}")
}

/// The ids builds before the digest gave this plan (`cmes_<number>_<KIND>`, or with
/// the name added), without the saver number.
pub fn legacy_external_ids(name: &str, plan_kind: Option<&str>) -> Vec<String> {
    let name_slug = slug(name);
    let slugs = match plan_kind {
        Some(kind) => vec![kind.to_owned(), format!("{kind}-{name_slug}")],
        None => vec![name_slug],
    };
    slugs
        .into_iter()
        .map(|s| format!("cmes_{s}").chars().take(100).collect())
        .collect()
}

// ─── Situation parser (services/cmes-auth/situation_parser.py) ──────────────

#[derive(Debug, Clone)]
pub struct SituationFormatError {
    pub code: &'static str,
    pub message: String,
}

impl SituationFormatError {
    pub fn new(code: &'static str, message: impl Into<String>) -> Self {
        Self {
            code,
            message: message.into(),
        }
    }

    pub fn into_sidecar_error(self) -> NativeSidecarError {
        UpstreamError::new(self.code, self.message).into_sidecar_error()
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SituationFund {
    pub label: String,
    pub button: String,
    pub value_eur: Decimal,
    pub gain_percent: Decimal,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SituationPlan {
    pub name: String,
    pub plan_kind: Option<String>,
    pub balance_eur: Decimal,
    pub funds: Vec<SituationFund>,
}

static PLAN_TABLE_RE: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r#"(?i)<table\b[^>]*class="[^"]*\btb_accordeon\b[^"]*"[^>]*>"#)
        .expect("static regex")
});
static TABLE_TAG_RE: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"(?i)<(/?)table\b[^>]*>").expect("static regex"));
static PLAN_HEADER_RE: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r#"(?s)<th\b[^>]*class="[^"]*\brupture\b[^"]*"[^>]*>(?P<label>.*?)</th>"#)
        .expect("static regex")
});
static PLAN_TOTAL_RE: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(
        r#"(?s)Montant total estim(?:é|&#233;|&eacute;)\s*</span>\s*<br\s*/?>\s*<span\b[^>]*class="[^"]*\bem\b[^"]*"[^>]*>(?P<amount>.*?)</span>"#,
    )
    .expect("static regex")
});
static FUND_TABLE_RE: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r#"(?i)<table\b[^>]*class="[^"]*\beir_xs_listtable\b[^"]*"[^>]*>"#)
        .expect("static regex")
});
static ROW_RE: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"(?s)<tr\b[^>]*>(?P<row>.*?)</tr>").expect("static regex"));
static FUND_NAME_RE: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r#"(?s)<a\b[^>]*class="[^"]*\bes_fund-clickable\b[^"]*"[^>]*>(?P<name>.*?)</a>"#)
        .expect("static regex")
});
static FUND_BUTTON_RE: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r#"name="(?P<button>_FID_GoFundSummary_selectedFundIndex:\d+)""#)
        .expect("static regex")
});
static CELL_RE: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"(?s)<td\b[^>]*>(?P<cell>.*?)</td>").expect("static regex"));
static VALUE_RE: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r#"(?s)<span\b[^>]*class="[^"]*\bnowrap\b[^"]*"[^>]*>(?P<amount>[^<]*)"#)
        .expect("static regex")
});
static GAIN_RE: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r#"(?s)<span\b[^>]*class="[^"]*\bes_pvl\b[^"]*"[^>]*>(?P<gain>[^<]*)"#)
        .expect("static regex")
});
static MONEY_RE: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r"^(?P<sign>[+-]?)\s*(?P<value>\d[\d\s]*(?:,\d+)?)\s*€$").expect("static regex")
});
static PERCENT_RE: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r"^(?P<sign>[+-]?)\s*(?P<value>\d+(?:,\d+)?)\s*%$").expect("static regex")
});
static EMPLOYER_RE: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(
        r#"(?s)class="[^"]*\bes_dashboard\b.*?<div\b[^>]*class="[^"]*\bei_fbtitleblock__header\b[^"]*"[^>]*>(?P<title>.*?)</div>"#,
    )
    .expect("static regex")
});
static EMPLOYER_PREFIX_RE: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"(?i)^Mon [ée]pargne salariale\s+").expect("static regex"));

static PLAN_KIND_PATTERNS: LazyLock<Vec<(Regex, &'static str)>> = LazyLock::new(|| {
    vec![
        (Regex::new(r"(?i)retraite").expect("static regex"), "PERCOL"),
        (Regex::new(r"(?i)groupe").expect("static regex"), "PEG"),
        (Regex::new(r"(?i)entreprise").expect("static regex"), "PEE"),
        (
            Regex::new(r"(?i)courant bloqu").expect("static regex"),
            "CCB",
        ),
    ]
});

fn parse_signed_decimal(digits: &str, sign: &str) -> Result<Decimal, SituationFormatError> {
    let cleaned: String = digits
        .chars()
        .filter(|c| !c.is_whitespace())
        .map(|c| if c == ',' { '.' } else { c })
        .collect();
    let value: Decimal = cleaned
        .parse()
        .map_err(|_| SituationFormatError::new(FORMAT_CHANGED, "Unreadable number"))?;
    Ok(if sign == "-" { -value } else { value })
}

pub fn parse_money(raw: &str) -> Result<Decimal, SituationFormatError> {
    let text = html_text(raw);
    let cap = MONEY_RE
        .captures(&text)
        .ok_or_else(|| SituationFormatError::new(FORMAT_CHANGED, "Unreadable amount"))?;
    parse_signed_decimal(&cap["value"], &cap["sign"])
}

pub fn parse_percent(raw: &str) -> Result<Decimal, SituationFormatError> {
    let text = html_text(raw);
    let cap = PERCENT_RE
        .captures(&text)
        .ok_or_else(|| SituationFormatError::new(FORMAT_CHANGED, "Unreadable gain percentage"))?;
    parse_signed_decimal(&cap["value"], &cap["sign"])
}

pub fn plan_kind(label: &str) -> Option<String> {
    for (re, kind) in PLAN_KIND_PATTERNS.iter() {
        if re.is_match(label) {
            return Some((*kind).to_owned());
        }
    }
    None
}

pub fn money_close(actual: Decimal, expected: Decimal) -> bool {
    let Some(diff) = actual.checked_sub(expected).map(|d| d.abs()) else {
        return false;
    };
    let abs_tol = Decimal::new(5, 2); // 0.05
    let Some(rel_tol) = expected.abs().checked_mul(Decimal::new(1, 3)) else {
        return false;
    }; // 0.001
    let tolerance = abs_tol.max(rel_tol);
    diff <= tolerance
}

fn table_at(page: &str, start: usize) -> Result<&str, SituationFormatError> {
    let mut depth: i32 = 0;
    for cap in TABLE_TAG_RE.captures_iter(&page[start..]) {
        let m = cap.get(0).expect("match");
        let closing = !cap.get(1).map_or("", |g| g.as_str()).is_empty();
        depth += if closing { -1 } else { 1 };
        if depth == 0 {
            return Ok(&page[start..start + m.end()]);
        }
    }
    Err(SituationFormatError::new(
        FORMAT_CHANGED,
        "Unterminated table",
    ))
}

fn parse_fund_row(row: &str) -> Result<Option<SituationFund>, SituationFormatError> {
    let Some(name_cap) = FUND_NAME_RE.captures(row) else {
        return Ok(None);
    };
    let button_cap = FUND_BUTTON_RE.captures(row);
    let cells: Vec<&str> = CELL_RE
        .captures_iter(row)
        .map(|c| c.name("cell").expect("cell").as_str())
        .collect();
    let value_cap = cells.get(1).and_then(|c| VALUE_RE.captures(c));
    let gain_cap = cells.get(2).and_then(|c| GAIN_RE.captures(c));
    let (Some(button), Some(value), Some(gain)) = (button_cap, value_cap, gain_cap) else {
        return Err(SituationFormatError::new(
            FORMAT_CHANGED,
            "A fund row lacks its button, value or gain",
        ));
    };
    Ok(Some(SituationFund {
        label: html_text(&name_cap["name"]),
        button: button["button"].to_owned(),
        value_eur: parse_money(&value["amount"])?,
        gain_percent: parse_percent(&gain["gain"])?,
    }))
}

fn parse_plan_table(table: &str) -> Result<SituationPlan, SituationFormatError> {
    let header = PLAN_HEADER_RE.captures(table);
    let total = PLAN_TOTAL_RE.captures(table);
    let fund_table_match = FUND_TABLE_RE.find(table);
    let (Some(header), Some(total), Some(fund_table_match)) = (header, total, fund_table_match)
    else {
        return Err(SituationFormatError::new(
            FORMAT_CHANGED,
            "A plan lacks its label, total or fund list",
        ));
    };
    let label = html_text(&header["label"]);
    let fund_table_html = table_at(table, fund_table_match.start())?;
    let mut funds = Vec::new();
    for row_cap in ROW_RE.captures_iter(fund_table_html) {
        if let Some(fund) = parse_fund_row(&row_cap["row"])? {
            funds.push(fund);
        }
    }
    let balance = parse_money(&total["amount"])?;
    if funds.is_empty() && !balance.is_zero() {
        return Err(SituationFormatError::new(
            INCOMPLETE,
            "A funded plan lists no fund",
        ));
    }
    let funds_sum = funds
        .iter()
        .try_fold(Decimal::ZERO, |acc, f| acc.checked_add(f.value_eur))
        .ok_or_else(|| SituationFormatError::new(FORMAT_CHANGED, "Fund values overflow"))?;
    if !money_close(funds_sum, balance) {
        return Err(SituationFormatError::new(
            INCOMPLETE,
            "The funds do not add up to the plan total",
        ));
    }
    Ok(SituationPlan {
        plan_kind: plan_kind(&label),
        name: label,
        balance_eur: balance,
        funds,
    })
}

/// The employer, from the title of the employee-savings dashboard ("Mon épargne
/// salariale <employer>"); it only labels the accounts, so a title worded otherwise
/// gives none rather than failing the read.
pub fn parse_employer(page: &str) -> Option<String> {
    let title = EMPLOYER_RE
        .captures(page)
        .map(|cap| html_text(&cap["title"]))?;
    let employer = EMPLOYER_PREFIX_RE.replace(&title, "").into_owned();
    (!employer.is_empty() && employer != title).then_some(employer)
}

pub fn parse_situation(page: &str) -> Result<Vec<SituationPlan>, SituationFormatError> {
    let starts: Vec<usize> = PLAN_TABLE_RE.find_iter(page).map(|m| m.start()).collect();
    if starts.is_empty() {
        return Err(SituationFormatError::new(
            FORMAT_CHANGED,
            "No plan table on the detailed situation page",
        ));
    }
    let mut plans = Vec::with_capacity(starts.len());
    let mut labels = HashSet::with_capacity(starts.len());
    for start in starts {
        let plan = parse_plan_table(table_at(page, start)?)?;
        if !labels.insert(plan.name.clone()) {
            return Err(SituationFormatError::new(
                FORMAT_CHANGED,
                "The same plan is listed twice",
            ));
        }
        plans.push(plan);
    }
    Ok(plans)
}

// ─── Fund parser (services/cmes-auth/fund_parser.py) ────────────────────────

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct FundSavingsDetails {
    pub value_eur: Decimal,
    pub pnl_eur: Decimal,
    pub quantity: Decimal,
    pub unit_value: Decimal,
    pub price_date: Option<NaiveDate>,
}

static KV_ROW_RE: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r"(?s)<tr\b[^>]*>\s*<th\b[^>]*>(?P<label>.*?)</th>\s*<td\b[^>]*>(?P<value>.*?)</td>")
        .expect("static regex")
});
static FUND_GAIN_RE: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r#"<span\b[^>]*class="[^"]*\bes_pvl\b[^"]*"[^>]*>(?P<gain>[^<]*)</span>"#)
        .expect("static regex")
});
static TABS_RE: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r#"(?s)<ul\b[^>]*class="[^"]*\bei_newtabs\b[^"]*"[^>]*>(?P<tabs>.*?)</ul>"#)
        .expect("static regex")
});
static TAB_LINK_RE: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r#"(?s)<a\b[^>]*href="(?P<href>[^"]+)"[^>]*>(?P<text>.*?)</a>"#)
        .expect("static regex")
});
static PRICE_DATE_RE: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"(?P<date>\d{2}/\d{2}/\d{4})").expect("static regex"));
static ISIN_RE: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"\b(?P<isin>[A-Z]{2}[A-Z0-9]{9}\d)\b").expect("static regex"));
static FUND_CODE_RE: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"^[\w-]{1,20}$").expect("static regex"));

pub fn parse_units(raw: &str) -> Result<Decimal, SituationFormatError> {
    let digits: String = html_text(raw)
        .chars()
        .filter(|c| !c.is_whitespace())
        .map(|c| if c == ',' { '.' } else { c })
        .collect();
    digits
        .parse::<Decimal>()
        .map_err(|_| SituationFormatError::new(FORMAT_CHANGED, "Unreadable number of units"))
}

pub fn my_savings_tab(page: &str) -> Option<String> {
    let tabs = TABS_RE.captures(page)?;
    for link in TAB_LINK_RE.captures_iter(&tabs["tabs"]) {
        let text = html_text(&link["text"]).to_lowercase();
        if text == "mon épargne" || text == "mes avoirs" {
            return Some(link["href"].replace("&amp;", "&"));
        }
    }
    None
}

pub fn parse_fund_identity(page: &str) -> (Option<String>, Option<String>) {
    let mut isin = None;
    let mut code = None;
    for row in KV_ROW_RE.captures_iter(page) {
        let label = html_text(&row["label"]).to_lowercase();
        let value = html_text(&row["value"]);
        if label.contains("isin") {
            isin = ISIN_RE.captures(&value).map(|c| c["isin"].to_owned());
        } else if label == "code du support" && FUND_CODE_RE.is_match(&value) {
            code = Some(value);
        }
    }
    (isin, code)
}

pub fn parse_my_savings(page: &str) -> Result<FundSavingsDetails, SituationFormatError> {
    let mut rows: HashMap<String, String> = HashMap::new();
    for row in KV_ROW_RE.captures_iter(page) {
        rows.insert(html_text(&row["label"]), row["value"].to_owned());
    }

    let value_of = |prefix: &str| -> Result<(String, String), SituationFormatError> {
        rows.iter()
            .find(|(k, _)| k.starts_with(prefix))
            .map(|(k, v)| (k.clone(), v.clone()))
            .ok_or_else(|| {
                SituationFormatError::new(
                    FORMAT_CHANGED,
                    format!("No '{prefix}' row on the fund page"),
                )
            })
    };

    let (_, amount) = value_of("Montant estimé")?;
    let (_, gain_cell) = value_of("Dont plus ou moins value")?;
    let (_, units) = value_of("Nombre de parts")?;
    let (price_label, price) = value_of("Valeur de la part")?;

    let gain = FUND_GAIN_RE.captures(&gain_cell).ok_or_else(|| {
        SituationFormatError::new(FORMAT_CHANGED, "No gain amount on the fund page")
    })?;
    let price_date = PRICE_DATE_RE
        .captures(&price_label)
        .and_then(|c| NaiveDate::parse_from_str(&c["date"], "%d/%m/%Y").ok());

    let value_eur = parse_money(&amount)?;
    let quantity = parse_units(&units)?;
    let unit_value = parse_money(&price)?;
    let computed = quantity.checked_mul(unit_value).ok_or_else(|| {
        SituationFormatError::new(FORMAT_CHANGED, "Units times unit value overflow")
    })?;
    if !money_close(computed, value_eur) {
        return Err(SituationFormatError::new(
            FORMAT_CHANGED,
            "Units times unit value do not give the fund value",
        ));
    }
    Ok(FundSavingsDetails {
        value_eur,
        pnl_eur: parse_money(&gain["gain"])?,
        quantity,
        unit_value,
        price_date,
    })
}
