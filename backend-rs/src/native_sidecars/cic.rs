//! Native Rust port of `services/cic-auth/` (CIC online banking accounts & balances).
//!
//! Endpoints:
//! - `GET /health` -> `{"status": "ok"}`
//! - `POST /initiate` `{login, password, sessionState?, unattended?}`
//! - `POST /complete` `{processId, code?}`
//! - `POST /accounts` `{sessionState}` -> `[{externalId, name, type, balanceEur, snapshotComplete}]`
//! - `POST /accounts/listing` `{sessionState}` -> `{accounts: [...], categoriesRead: ["CHECKING"|"SAVINGS"]}`

use std::collections::HashSet;
use std::sync::LazyLock;
use std::time::Duration;

use async_trait::async_trait;
use http::{Method, StatusCode};
use regex::Regex;
use rust_decimal::Decimal;
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use unicode_normalization::UnicodeNormalization;

use super::common::unescape_html;
use super::euro_info::{
    APP_VALIDATION_POLL_SECONDS, APP_VALIDATION_TIMEOUT_SECONDS, EuroInfoClient, Site,
    UpstreamError,
};
use super::{NativeSidecar, NativeSidecarError};

pub const DEFAULT_BASE_URL: &str = "https://www.cic.fr";
pub const LOGIN_PATH: &str = "/fr/authentification.html";
pub const ACCOUNTS_PATH: &str = "/fr/banque/comptes-et-contrats.html";
pub const DEFAULT_STATE_PATH: &str = "/fr/banque/async/otp/SOSD_OTP_GetTransactionState.htm";
pub const DEFAULT_CANCEL_PATH: &str = "/fr/banque/async/otp/SOSD_OTP_CancelTransaction.htm";

pub const FORMAT_CHANGED: &str = "UPSTREAM_FORMAT_CHANGED";
pub const CHECKING_CATEGORY: &str = "comptes courants";
pub const SAVINGS_CATEGORY: &str = "livrets et comptes d'épargne";

const ACTION_NEEDED_MARKERS: &[&str] = &[
    "conditions-generales",
    "paci_wsd_pdta",
    "residencefiscale",
    "migr_gestion_pouvoirs",
];
const CIC_SKIP_PHRASES: &[&str] = &["passer cette etape", "passer cette étape", "plus tard"];

pub fn default_site(base_url: &str) -> Site {
    Site {
        name: "CIC",
        base_url: base_url.to_owned(),
        login_path: LOGIN_PATH,
        logged_in_marker: "deconnexion/deconnexion.cgi",
        action_needed_path_markers: ACTION_NEEDED_MARKERS,
        skip_phrases: CIC_SKIP_PHRASES,
        postpone_button: Some("_FID_DoStopPaciAndRemind"),
        default_state_path: Some(DEFAULT_STATE_PATH),
        default_cancel_path: Some(DEFAULT_CANCEL_PATH),
        replayed_cookies: None,
        unreadable_poll_expires: false,
        app_validation_timeout: Duration::from_secs(APP_VALIDATION_TIMEOUT_SECONDS),
        app_validation_poll: Duration::from_secs(APP_VALIDATION_POLL_SECONDS),
    }
}

pub struct NativeCic {
    euro: EuroInfoClient,
}

impl Default for NativeCic {
    fn default() -> Self {
        Self::with_base_url(DEFAULT_BASE_URL)
    }
}

impl NativeCic {
    pub fn with_base_url(base_url: &str) -> Self {
        Self::with_site(default_site(base_url))
    }

    pub fn with_site(site: Site) -> Self {
        Self {
            euro: EuroInfoClient::new(site),
        }
    }

    async fn read_accounts(&self, body: Value) -> Result<AccountsResponse, NativeSidecarError> {
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

        let page = self
            .euro
            .fetch_page(&client, ACCOUNTS_PATH, "Accounts")
            .await?;
        let (parsed, skipped, categories) = parse_accounts_listing(&page).map_err(|e| {
            tracing::warn!("CIC accounts rejected (code={}): {}", e.code, e.message);
            UpstreamError::new(e.code, e.message).into_sidecar_error()
        })?;
        if !skipped.is_empty() {
            let bounded: Vec<String> = skipped
                .iter()
                .map(|c| c.chars().take(60).collect())
                .collect();
            tracing::info!("CIC: skipped out-of-scope categories {:?}", bounded);
        }
        if parsed.is_empty() {
            return Err(UpstreamError::new(
                "PORTFOLIO_INCOMPLETE",
                "No current or savings account on the page",
            )
            .into_sidecar_error());
        }

        let mut accounts = Vec::with_capacity(parsed.len());
        for a in parsed {
            let external_id = format!("cic_{}", a.id);
            if external_id.is_empty()
                || external_id.chars().count() > 100
                || a.name.is_empty()
                || a.name.chars().count() > 200
            {
                return Err(NativeSidecarError::new(
                    StatusCode::BAD_GATEWAY,
                    "INVALID_DATA",
                ));
            }
            accounts.push(AccountPayload {
                external_id,
                name: a.name,
                account_type: a.account_type,
                balance_eur: a.balance_eur,
                snapshot_complete: true,
            });
        }

        Ok(AccountsResponse {
            accounts,
            categories_read: categories,
        })
    }
}

#[async_trait]
impl NativeSidecar for NativeCic {
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
            (Method::POST, "/accounts") => {
                let listing = self.read_accounts(body).await?;
                Ok(serde_json::to_value(listing.accounts).expect("serialize CIC accounts"))
            }
            (Method::POST, "/accounts/listing") => {
                let listing = self.read_accounts(body).await?;
                Ok(serde_json::to_value(listing).expect("serialize CIC listing"))
            }
            _ => Err(NativeSidecarError::new(StatusCode::NOT_FOUND, "Not Found")),
        }
    }
}

// ─── Contract payloads ──────────────────────────────────────────────────────

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AccountPayload {
    pub external_id: String,
    pub name: String,
    #[serde(rename = "type")]
    pub account_type: &'static str,
    pub balance_eur: Decimal,
    pub snapshot_complete: bool,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AccountsResponse {
    pub accounts: Vec<AccountPayload>,
    pub categories_read: Vec<&'static str>,
}

// ─── Accounts parser (port of services/cic-auth/accounts_parser.py) ─────────

#[derive(Debug, Clone)]
pub struct AccountsFormatError {
    pub code: &'static str,
    pub message: String,
}

impl AccountsFormatError {
    pub fn new(code: &'static str, message: impl Into<String>) -> Self {
        Self {
            code,
            message: message.into(),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ParsedAccount {
    pub id: String,
    pub name: String,
    pub account_type: &'static str,
    pub balance_eur: Decimal,
}

static DOCUMENT_END_RE: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"(?i)</html\s*>\s*$").expect("static regex"));
static TABLE_RE: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r#"(?s)<table\b[^>]*class="[^"]*\bei_comptescontrats\b[^"]*"[^>]*>(?P<body>.*?)(?P<closing_tag></table>|$)"#)
        .expect("static regex")
});
static UNPARSED_OPEN_RE: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"(?i)<(?P<tag>table|tr)\b[^>]*>").expect("static regex"));
static CLOSE_TABLE_RE: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"(?i)</table>").expect("static regex"));
static CLOSE_TR_RE: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"(?i)</tr>").expect("static regex"));
static CAPTION_RE: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r"(?s)<caption\b[^>]*>(?P<text>.*?)</caption>").expect("static regex")
});
static CATEGORY_RE: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"(?i)cat[ée]gorie\s*:\s*(?P<name>.+)$").expect("static regex"));
static ROW_RE: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"(?is)<tr\b[^>]*>(?P<row>.*?)</tr>").expect("static regex"));
static TITLE_RE: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r#"(?s)class="[^"]*\bei_sdsf_title\b[^"]*"[^>]*>(?P<title>.*?)</span>"#)
        .expect("static regex")
});
static NUMBER_RE: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r#"(?s)class="[^"]*\bdoux\b[^"]*"[^>]*>(?P<number>.*?)</span>"#)
        .expect("static regex")
});
static AMOUNT_RE: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r#"(?s)class="[^"]*\bei_sdsf_montant\b[^"]*"[^>]*>(?P<amount>.*?)</span>"#)
        .expect("static regex")
});
static MONEY_RE: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r"^(?P<sign>[+-]?)\s*(?P<value>\d[\d\s.]*(?:,\d+)?)\s*(?P<currency>[A-Z]{3}|€)$")
        .expect("static regex")
});
static GROUPED_THOUSANDS_RE: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"^\d{1,3}(?:\s+\d{3})+$").expect("static regex"));
static DECIMAL_IN_NUM_RE: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"[.,]\d").expect("static regex"));
static EURO_WORD_START_RE: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"(?i)^euros?\b").expect("static regex"));

static SAVINGS_TYPES: LazyLock<Vec<(Regex, &'static str)>> = LazyLock::new(|| {
    vec![
        (
            Regex::new(r"\bLIVRET A\b").expect("static regex"),
            "LIVRET_A",
        ),
        (
            Regex::new(r"\bLDDS?\b|DEVELOPPEMENT DURABLE").expect("static regex"),
            "LDDS",
        ),
        (
            Regex::new(r"\bLEP\b|EPARGNE POPULAIRE").expect("static regex"),
            "LEP",
        ),
        (
            Regex::new(r"LIVRET JEUNE").expect("static regex"),
            "LIVRET_JEUNE",
        ),
        (
            Regex::new(r"PLAN (D')?EPARGNE LOGEMENT|\bPEL\b").expect("static regex"),
            "PEL",
        ),
        (
            Regex::new(r"COMPTE (D')?EPARGNE LOGEMENT|\bCEL\b").expect("static regex"),
            "CEL",
        ),
    ]
});

static CURRENCY_CODES: LazyLock<HashSet<&'static str>> = LazyLock::new(|| {
    ("AED AFN ALL AMD AOA ARS AUD AWG AZN BAM BBD BDT BHD BIF BMD BND BOB BOV BRL BSD \
         BTN BWP BYN BZD CAD CDF CHE CHF CHW CLF CLP CNY COP COU CRC CUP CVE CZK DJF DKK \
         DOP DZD EGP ERN ETB EUR FJD FKP GBP GEL GHS GIP GMD GNF GTQ GYD HKD HNL HTG HUF \
         IDR ILS INR IQD IRR ISK JMD JOD JPY KES KGS KHR KMF KPW KRW KWD KYD KZT LAK LBP \
         LKR LRD LSL LYD MAD MDL MGA MKD MMK MNT MOP MRU MUR MVR MWK MXN MXV MYR MZN NAD \
         NGN NIO NOK NPR NZD OMR PAB PEN PGK PHP PKR PLN PYG QAR RON RSD RUB RWF SAR SBD \
         SCR SDG SEK SGD SHP SLE SOS SRD SSP STN SVC SYP SZL THB TJS TMT TND TOP TRY TTD \
         TWD TZS UAH UGX USD USN UYI UYU UYW UZS VED VES VND VUV WST XAD XAF XAG XAU XBA \
         XBB XBC XBD XCD XCG XDR XOF XPD XPF XPT XSU XTS XUA XXX YER ZAR ZMW ZWG")
        .split_whitespace()
        .collect()
});

const AMBIGUOUS_CURRENCY_PREFIXES: &[&str] = &["ALL", "SOS", "TOP"];

const VOID_TAGS: &[&str] = &[
    "area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param", "source",
    "track", "wbr",
];

// ─── HTML preprocessing (_PageParser) ───────────────────────────────────────

/// Strips `<script>...</script>`, `<style>...</style>`, HTML comments, and bogus `<!...>`
/// declarations while respecting quoted attributes so `<div title="<script>">` never hides markup.
pub fn visible_html(page: &str) -> String {
    let bytes = page.as_bytes();
    let len = bytes.len();
    let mut hidden: Vec<(usize, usize)> = Vec::new();
    let mut i = 0;

    while i < len {
        if bytes[i] != b'<' {
            i += 1;
            continue;
        }
        // HTML comment or bogus declaration
        if i + 1 < len && bytes[i + 1] == b'!' {
            if page[i..].starts_with("<!--") {
                let end = if page[i..].starts_with("<!-->") {
                    i + 5
                } else if page[i..].starts_with("<!--->") {
                    i + 6
                } else if let Some(rel) = page[i + 4..].find("-->") {
                    i + 4 + rel + 3
                } else {
                    len
                };
                hidden.push((i, end));
                i = end;
                continue;
            }
            // <!DOCTYPE ...> is kept; bogus declarations like <!help> are hidden like HTMLParser comments.
            let is_doctype = page[i + 2..]
                .get(..7)
                .is_some_and(|s| s.eq_ignore_ascii_case("doctype"));
            let end = page[i + 2..].find('>').map_or(len, |rel| i + 2 + rel + 1);
            if !is_doctype {
                hidden.push((i, end));
            }
            i = end;
            continue;
        }

        // Start tag
        if i + 1 < len && bytes[i + 1].is_ascii_alphabetic() {
            let tag_start = i;
            let name_start = i + 1;
            let mut j = name_start;
            while j < len && (bytes[j].is_ascii_alphanumeric() || matches!(bytes[j], b'-' | b':')) {
                j += 1;
            }
            let tag_name = &page[name_start..j];
            let tag_end = scan_tag_end(bytes, j);
            if tag_name.eq_ignore_ascii_case("script") || tag_name.eq_ignore_ascii_case("style") {
                let close_end = find_rawtext_close(page, tag_end, tag_name).unwrap_or(len);
                hidden.push((tag_start, close_end));
                i = close_end;
                continue;
            }
            i = tag_end;
            continue;
        }

        i += 1;
    }

    if hidden.is_empty() {
        return page.to_owned();
    }
    let mut parts: Vec<&str> = Vec::with_capacity(hidden.len() + 1);
    let mut end = 0;
    for (start, stop) in hidden {
        parts.push(&page[end..start]);
        end = stop;
    }
    parts.push(&page[end..]);
    parts.join(" ")
}

fn scan_tag_end(bytes: &[u8], mut i: usize) -> usize {
    let len = bytes.len();
    let mut quote: Option<u8> = None;
    while i < len {
        let b = bytes[i];
        match quote {
            Some(q) if b == q => quote = None,
            Some(_) => {}
            None if b == b'"' || b == b'\'' => quote = Some(b),
            None if b == b'>' => return i + 1,
            None => {}
        }
        i += 1;
    }
    len
}

fn find_rawtext_close(page: &str, mut from: usize, tag_name: &str) -> Option<usize> {
    let bytes = page.as_bytes();
    let len = bytes.len();
    let tag_len = tag_name.len();
    while from < len {
        let rel = page[from..].find("</")?;
        let pos = from + rel;
        let name_start = pos + 2;
        if page
            .get(name_start..name_start + tag_len)
            .is_some_and(|s| s.eq_ignore_ascii_case(tag_name))
        {
            let after = name_start + tag_len;
            if after < len && (bytes[after] == b'>' || bytes[after].is_ascii_whitespace()) {
                let gt = page[after..].find('>')?;
                return Some(after + gt + 1);
            }
        }
        from = pos + 2;
    }
    None
}

// ─── Fragment text & marker parser (_TextParser) ────────────────────────────

struct TextState {
    parts: Vec<String>,
    outside_title_parts: Vec<String>,
    classes: HashSet<String>,
    in_title: bool,
    implicitly_closed_title: bool,
    in_balance_cell: bool,
    has_balance_text: bool,
    stack: Vec<(String, bool, bool)>,
}

impl TextState {
    fn new() -> Self {
        Self {
            parts: Vec::new(),
            outside_title_parts: Vec::new(),
            classes: HashSet::new(),
            in_title: false,
            implicitly_closed_title: false,
            in_balance_cell: false,
            has_balance_text: false,
            stack: Vec::new(),
        }
    }

    fn handle_starttag(&mut self, tag: &str, raw_attrs: &str, self_closing: bool) {
        if tag == "td" || tag == "th" {
            for (open_tag, _, _) in self.stack.iter().rev() {
                if open_tag == "td" || open_tag == "th" {
                    let close_tag = open_tag.clone();
                    self.implicitly_closed_title |= self.in_title;
                    self.handle_endtag(&close_tag);
                    break;
                }
            }
        }
        let tokens = extract_class_tokens(raw_attrs);
        let is_title = tokens.iter().any(|t| t == "ei_sdsf_title");
        let is_solde = tokens.iter().any(|t| t == "ei_sdsf_solde");
        for tok in tokens {
            self.classes.insert(tok);
        }
        if !VOID_TAGS.contains(&tag) && !self_closing {
            self.stack
                .push((tag.to_owned(), self.in_title, self.in_balance_cell));
            self.in_title |= is_title;
            self.in_balance_cell |= is_solde;
        }
    }

    fn handle_endtag(&mut self, tag: &str) {
        if let Some(index) = self.stack.iter().rposition(|(open, _, _)| open == tag) {
            let (_, was_title, was_balance) = self.stack[index];
            if self.in_title && !was_title && index + 1 < self.stack.len() {
                self.implicitly_closed_title = true;
            }
            self.in_title = was_title;
            self.in_balance_cell = was_balance;
            self.stack.truncate(index);
        }
    }

    fn handle_data(&mut self, raw: &str) {
        let data = unescape_html(raw);
        if self.in_balance_cell && !data.trim().is_empty() {
            self.has_balance_text = true;
        }
        if !self.in_title {
            self.outside_title_parts.push(data.clone());
        }
        self.parts.push(data);
    }
}

fn parse_text(fragment: &str) -> TextState {
    let mut state = TextState::new();
    let bytes = fragment.as_bytes();
    let len = bytes.len();
    let mut i = 0;
    let mut text_start = 0;

    while i < len {
        if bytes[i] != b'<' {
            i += 1;
            continue;
        }
        if i > text_start {
            state.handle_data(&fragment[text_start..i]);
        }

        // Comment or bogus declaration
        if i + 1 < len && bytes[i + 1] == b'!' {
            let end = if fragment[i..].starts_with("<!--") {
                if fragment[i..].starts_with("<!-->") {
                    i + 5
                } else if fragment[i..].starts_with("<!--->") {
                    i + 6
                } else if let Some(rel) = fragment[i + 4..].find("-->") {
                    i + 4 + rel + 3
                } else {
                    len
                }
            } else {
                fragment[i + 2..]
                    .find('>')
                    .map_or(len, |rel| i + 2 + rel + 1)
            };
            i = end;
            text_start = i;
            continue;
        }

        // End tag
        if i + 1 < len && bytes[i + 1] == b'/' {
            let name_start = i + 2;
            let mut j = name_start;
            while j < len && (bytes[j].is_ascii_alphanumeric() || matches!(bytes[j], b'-' | b':')) {
                j += 1;
            }
            let tag = fragment[name_start..j].to_ascii_lowercase();
            let end = fragment[j..].find('>').map_or(len, |rel| j + rel + 1);
            if !tag.is_empty() {
                state.handle_endtag(&tag);
            }
            i = end;
            text_start = i;
            continue;
        }

        // Start tag
        if i + 1 < len && bytes[i + 1].is_ascii_alphabetic() {
            let name_start = i + 1;
            let mut j = name_start;
            while j < len && (bytes[j].is_ascii_alphanumeric() || matches!(bytes[j], b'-' | b':')) {
                j += 1;
            }
            let tag = fragment[name_start..j].to_ascii_lowercase();
            let tag_end = scan_tag_end(bytes, j);
            let inner_end = if tag_end > j && bytes[tag_end - 1] == b'>' {
                tag_end - 1
            } else {
                tag_end
            };
            let raw_attrs = &fragment[j..inner_end];
            let self_closing = raw_attrs.trim_end().ends_with('/');
            if tag == "script" || tag == "style" {
                let close_end = find_rawtext_close(fragment, tag_end, &tag).unwrap_or(len);
                i = close_end;
                text_start = i;
                continue;
            }
            state.handle_starttag(&tag, raw_attrs, self_closing);
            i = tag_end;
            text_start = i;
            continue;
        }

        // Literal '<' not starting a tag
        state.handle_data("<");
        i += 1;
        text_start = i;
    }

    if text_start < len {
        state.handle_data(&fragment[text_start..]);
    }
    state
}

fn extract_class_tokens(raw_attrs: &str) -> Vec<String> {
    let bytes = raw_attrs.as_bytes();
    let len = bytes.len();
    let mut i = 0;
    while i < len {
        while i < len && (bytes[i].is_ascii_whitespace() || bytes[i] == b'/') {
            i += 1;
        }
        let name_start = i;
        while i < len && !bytes[i].is_ascii_whitespace() && !matches!(bytes[i], b'=' | b'/' | b'>')
        {
            i += 1;
        }
        let name = &raw_attrs[name_start..i];
        while i < len && bytes[i].is_ascii_whitespace() {
            i += 1;
        }
        let mut value = None;
        if i < len && bytes[i] == b'=' {
            i += 1;
            while i < len && bytes[i].is_ascii_whitespace() {
                i += 1;
            }
            if i < len && (bytes[i] == b'"' || bytes[i] == b'\'') {
                let quote = bytes[i];
                i += 1;
                let val_start = i;
                while i < len && bytes[i] != quote {
                    i += 1;
                }
                value = Some(&raw_attrs[val_start..i]);
                if i < len {
                    i += 1;
                }
            } else {
                let val_start = i;
                while i < len && !bytes[i].is_ascii_whitespace() && !matches!(bytes[i], b'/' | b'>')
                {
                    i += 1;
                }
                value = Some(&raw_attrs[val_start..i]);
            }
        }
        if name.eq_ignore_ascii_case("class")
            && let Some(val) = value
        {
            return unescape_html(val)
                .split_whitespace()
                .map(str::to_owned)
                .collect();
        }
    }
    Vec::new()
}

fn text_of(fragment: &str) -> String {
    parse_text(fragment)
        .parts
        .join(" ")
        .split_whitespace()
        .collect::<Vec<_>>()
        .join(" ")
}

// ─── Linear-time money / balance scanner ────────────────────────────────────

struct BalanceMatch<'a> {
    prefix: Option<&'a str>,
    number: &'a str,
    suffix: Option<&'a str>,
    end: usize,
}

fn is_word_char(ch: char) -> bool {
    ch.is_alphanumeric() || ch == '_'
}

fn match_currency_token(
    text: &str,
    pos: usize,
    require_leading_boundary: bool,
) -> Option<(&str, usize)> {
    let rest = &text[pos..];
    let first = rest.chars().next()?;
    if matches!(first, '€' | '$' | '£') {
        let end = pos + first.len_utf8();
        return Some((&text[pos..end], end));
    }
    if require_leading_boundary
        && pos > 0
        && text[..pos].chars().next_back().is_some_and(is_word_char)
    {
        return None;
    }
    let bytes = rest.as_bytes();
    if bytes.len() < 3 || !bytes[..3].iter().all(u8::is_ascii_alphabetic) {
        return None;
    }
    let candidate = &rest[..3];
    let is_upper_3 = candidate.bytes().all(|b| b.is_ascii_uppercase());
    let is_eur_ci = candidate.eq_ignore_ascii_case("EUR");
    if !is_upper_3 && !is_eur_ci {
        return None;
    }
    let after = pos + 3;
    if after < text.len() && text[after..].chars().next().is_some_and(is_word_char) {
        return None;
    }
    Some((candidate, after))
}

fn scan_balance_matches(text: &str) -> Vec<BalanceMatch<'_>> {
    let mut matches = Vec::new();
    let len = text.len();
    let mut i = 0;

    while i < len {
        // Attempt optional currency prefix followed by whitespace*, optional [+-], whitespace*, digit.
        let mut prefix: Option<&str> = None;
        let mut num_start: Option<usize> = None;

        if let Some((tok, after_tok)) = match_currency_token(text, i, true) {
            let mut j = after_tok;
            while let Some(ch) = text[j..].chars().next() {
                if ch.is_whitespace() {
                    j += ch.len_utf8();
                } else {
                    break;
                }
            }
            if let Some(ch) = text[j..].chars().next()
                && (ch == '+' || ch == '-')
            {
                j += 1;
                while let Some(ws) = text[j..].chars().next() {
                    if ws.is_whitespace() {
                        j += ws.len_utf8();
                    } else {
                        break;
                    }
                }
            }
            if text[j..].chars().next().is_some_and(|c| c.is_ascii_digit()) {
                prefix = Some(tok);
                num_start = Some(j);
            }
        }

        if num_start.is_none() && text[i..].chars().next().is_some_and(|c| c.is_ascii_digit()) {
            num_start = Some(i);
        }

        let Some(ns) = num_start else {
            let ch = text[i..].chars().next().expect("valid utf8");
            i += ch.len_utf8();
            continue;
        };

        let mut j = ns;
        while let Some(ch) = text[j..].chars().next() {
            if ch.is_ascii_digit() || ch.is_whitespace() || ch == '.' || ch == ',' {
                j += ch.len_utf8();
            } else {
                break;
            }
        }
        let number = &text[ns..j];
        let (suffix, end) = match match_currency_token(text, j, false) {
            Some((tok, after)) => (Some(tok), after),
            None => (None, j),
        };
        matches.push(BalanceMatch {
            prefix,
            number,
            suffix,
            end,
        });
        i = end;
    }

    matches
}

fn is_known_currency(token: &str) -> bool {
    matches!(token, "€" | "$" | "£") || CURRENCY_CODES.contains(token.to_ascii_uppercase().as_str())
}

fn has_money(text: &str, explicit: bool, contract: bool) -> bool {
    for m in scan_balance_matches(text) {
        let following = text[m.end..].chars().next();
        if m.suffix.is_none()
            && m.number
                .chars()
                .next_back()
                .is_some_and(|c| c.is_ascii_digit())
            && following.is_some_and(|c| c.is_alphanumeric() || c == '_' || c == '/')
        {
            continue;
        }
        let prefix_curr = m.prefix.is_some_and(is_known_currency);
        let suffix_curr = m.suffix.is_some_and(is_known_currency);
        if !prefix_curr && !suffix_curr {
            continue;
        }
        let has_decimal = DECIMAL_IN_NUM_RE.is_match(m.number);
        let compact_len = m.number.chars().filter(|c| !c.is_whitespace()).count();
        let large_whole = compact_len >= 4;
        if !contract
            && !suffix_curr
            && m.prefix
                .is_some_and(|p| AMBIGUOUS_CURRENCY_PREFIXES.contains(&p))
            && !has_decimal
            && !large_whole
        {
            continue;
        }
        let has_symbol = m.prefix.is_some_and(|p| matches!(p, "€" | "$" | "£"))
            || m.suffix.is_some_and(|s| matches!(s, "€" | "$" | "£"));
        if !explicit || has_symbol || has_decimal || GROUPED_THOUSANDS_RE.is_match(m.number.trim())
        {
            return true;
        }
    }
    false
}

/// Checks for `,\d{2}\b(?!\s*%)` in linear time.
fn has_bare_decimal(s: &str) -> bool {
    let bytes = s.as_bytes();
    let len = bytes.len();
    let mut i = 0;
    while i + 2 < len {
        if bytes[i] == b',' && bytes[i + 1].is_ascii_digit() && bytes[i + 2].is_ascii_digit() {
            let after = i + 3;
            let boundary_ok = after == len || !s[after..].chars().next().is_some_and(is_word_char);
            if boundary_ok {
                let mut k = after;
                while let Some(ch) = s[k..].chars().next() {
                    if ch.is_whitespace() {
                        k += ch.len_utf8();
                    } else {
                        break;
                    }
                }
                if !s[k..].starts_with('%') {
                    return true;
                }
            }
        }
        i += 1;
    }
    false
}

fn balance_data(parser: &TextState, contract: bool) -> bool {
    if parser.classes.contains("ei_sdsf_montant")
        || parser.classes.contains("doux")
        || parser.has_balance_text
    {
        return true;
    }
    let outside = parser.outside_title_parts.join(" ");
    if has_money(&outside, false, contract) {
        return true;
    }
    let all = parser.parts.join(" ");
    has_money(
        &all,
        !(parser.in_title || parser.implicitly_closed_title),
        contract,
    )
}

pub fn has_balance(fragment: &str) -> bool {
    let parser = parse_text(fragment);
    if balance_data(&parser, false) {
        return true;
    }
    let outside_title = parser.outside_title_parts.join(" ");
    for m in scan_balance_matches(&outside_title) {
        let mut combined = m.number.to_owned();
        if let Some(next_ch) = outside_title[m.end..].chars().next() {
            combined.push(next_ch);
        }
        if has_bare_decimal(&combined) || EURO_WORD_START_RE.is_match(&outside_title[m.end..]) {
            return true;
        }
    }
    false
}

fn has_account_data(fragment: &str, contract: bool) -> bool {
    let parser = parse_text(fragment);
    parser.classes.contains("ei_sdsf_title")
        || parser.classes.contains("doux")
        || parser.classes.contains("ei_sdsf_solde")
        || balance_data(&parser, contract)
}

// ─── Public parser API ──────────────────────────────────────────────────────

pub fn parse_amount(raw: &str) -> Result<Decimal, AccountsFormatError> {
    let normalized = text_of(raw).replace(['\u{00A0}', '\u{202F}'], " ");
    let cap = MONEY_RE
        .captures(&normalized)
        .ok_or_else(|| AccountsFormatError::new(FORMAT_CHANGED, "Unreadable account balance"))?;
    let currency = &cap["currency"];
    if currency != "EUR" && currency != "€" {
        return Err(AccountsFormatError::new(
            FORMAT_CHANGED,
            "Balance is not in euros",
        ));
    }
    let digits: String = cap["value"]
        .chars()
        .filter(|c| !c.is_whitespace() && *c != '.')
        .map(|c| if c == ',' { '.' } else { c })
        .collect();
    let value: Decimal = digits
        .parse()
        .map_err(|_| AccountsFormatError::new(FORMAT_CHANGED, "Unreadable account balance"))?;
    Ok(if &cap["sign"] == "-" { -value } else { value })
}

fn fold_upper(label: &str) -> String {
    label
        .replace('\u{2019}', "'")
        .nfkd()
        .filter(|c| !is_combining_mark(*c))
        .collect::<String>()
        .to_uppercase()
}

fn is_combining_mark(c: char) -> bool {
    matches!(
        c as u32,
        0x0300..=0x036F | 0x1AB0..=0x1AFF | 0x1DC0..=0x1DFF | 0x20D0..=0x20FF | 0xFE20..=0xFE2F
    )
}

pub fn account_type(category: &str, label: &str) -> &'static str {
    if category == CHECKING_CATEGORY {
        return "CHECKING";
    }
    let upper = fold_upper(label);
    for (re, kind) in SAVINGS_TYPES.iter() {
        if re.is_match(&upper) {
            return kind;
        }
    }
    "SAVINGS"
}

fn category_of_table(table: &str) -> String {
    let Some(caption) = CAPTION_RE.captures(table) else {
        return String::new();
    };
    let text = text_of(&caption["text"]);
    match CATEGORY_RE.captures(&text) {
        Some(cap) => cap["name"].trim().to_lowercase().replace('\u{2019}', "'"),
        None => String::new(),
    }
}

fn title_case(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    let mut prev_cased = false;
    for ch in s.chars() {
        if ch.is_alphabetic() {
            if prev_cased {
                for lc in ch.to_lowercase() {
                    out.push(lc);
                }
            } else {
                for uc in ch.to_uppercase() {
                    out.push(uc);
                }
            }
            prev_cased = true;
        } else {
            out.push(ch);
            prev_cased = false;
        }
    }
    out
}

fn category_group(category: &str) -> Option<&'static str> {
    if category == CHECKING_CATEGORY {
        Some("CHECKING")
    } else if category == SAVINGS_CATEGORY {
        Some("SAVINGS")
    } else {
        None
    }
}

pub fn categories_read(page: &str) -> Vec<&'static str> {
    let visible = visible_html(page);
    let mut found = HashSet::new();
    for cap in TABLE_RE.captures_iter(&visible) {
        found.insert(category_of_table(&cap["body"]));
    }
    let mut out = Vec::new();
    if found.contains(CHECKING_CATEGORY) {
        out.push("CHECKING");
    }
    if found.contains(SAVINGS_CATEGORY) {
        out.push("SAVINGS");
    }
    out
}

fn unparsed_container_has_account_data(remainder: &str) -> bool {
    let mut pos = 0;
    while pos < remainder.len() {
        let Some(cap) = UNPARSED_OPEN_RE.captures(&remainder[pos..]) else {
            break;
        };
        let open = cap.get(0).expect("open match");
        let tag = cap["tag"].to_ascii_lowercase();
        let body_start = pos + open.end();
        let close_re = if tag == "table" {
            &*CLOSE_TABLE_RE
        } else {
            &*CLOSE_TR_RE
        };
        let (body, next_pos) = match close_re.find(&remainder[body_start..]) {
            Some(close) => (
                &remainder[body_start..body_start + close.start()],
                body_start + close.end(),
            ),
            None => (&remainder[body_start..], remainder.len()),
        };
        if has_account_data(body, false) {
            return true;
        }
        pos = next_pos;
    }
    false
}

pub fn parse_accounts(
    page: &str,
) -> Result<(Vec<ParsedAccount>, Vec<String>), AccountsFormatError> {
    let (accounts, skipped, _) = parse_accounts_listing(page)?;
    Ok((accounts, skipped))
}

pub type AccountsListing = (Vec<ParsedAccount>, Vec<String>, Vec<&'static str>);

pub fn parse_accounts_listing(page: &str) -> Result<AccountsListing, AccountsFormatError> {
    if !DOCUMENT_END_RE.is_match(page) {
        return Err(AccountsFormatError::new(
            FORMAT_CHANGED,
            "Incomplete accounts page",
        ));
    }

    let visible = visible_html(page);
    let tables: Vec<regex::Captures<'_>> = TABLE_RE.captures_iter(&visible).collect();
    if tables.is_empty() {
        return Err(AccountsFormatError::new(
            FORMAT_CHANGED,
            "No contract table on the accounts page",
        ));
    }
    if tables.iter().any(|t| t["closing_tag"].is_empty()) {
        return Err(AccountsFormatError::new(
            FORMAT_CHANGED,
            "Unterminated contract table on the accounts page",
        ));
    }
    let remainder = TABLE_RE.replace_all(&visible, "");
    if unparsed_container_has_account_data(&remainder) {
        return Err(AccountsFormatError::new(
            FORMAT_CHANGED,
            "Account data outside the contract tables",
        ));
    }

    let mut accounts: Vec<ParsedAccount> = Vec::new();
    let mut skipped: Vec<String> = Vec::new();
    let mut seen: HashSet<String> = HashSet::new();
    let mut found: HashSet<String> = HashSet::new();

    for table in tables {
        let body = &table["body"];
        let category = category_of_table(body);
        let supported = category_group(&category).is_some();
        if supported {
            found.insert(category.clone());
        }
        let outside_rows = ROW_RE.replace_all(body, "");
        if has_account_data(&outside_rows, supported) {
            return Err(AccountsFormatError::new(
                FORMAT_CHANGED,
                "Account data outside a recognized account row",
            ));
        }

        for row_cap in ROW_RE.captures_iter(body) {
            let cells = &row_cap["row"];
            if !supported {
                if has_balance(cells) {
                    let display_cat: String = if category.is_empty() {
                        "unnamed".to_owned()
                    } else {
                        category.chars().take(60).collect()
                    };
                    return Err(AccountsFormatError::new(
                        FORMAT_CHANGED,
                        format!("An account table has an unrecognized category '{display_cat}'"),
                    ));
                }
                continue;
            }
            if !has_account_data(cells, true) {
                continue;
            }
            let titles: Vec<_> = TITLE_RE.captures_iter(cells).collect();
            let numbers: Vec<_> = NUMBER_RE.captures_iter(cells).collect();
            let amounts: Vec<_> = AMOUNT_RE.captures_iter(cells).collect();
            if titles.len() != 1 || numbers.len() != 1 || amounts.len() != 1 {
                return Err(AccountsFormatError::new(
                    FORMAT_CHANGED,
                    "An account row has no unique title, number, or balance",
                ));
            }
            let account_id: String = text_of(&numbers[0]["number"])
                .chars()
                .filter(char::is_ascii_digit)
                .collect();
            if account_id.is_empty() {
                return Err(AccountsFormatError::new(
                    FORMAT_CHANGED,
                    "An account row has an empty number",
                ));
            }
            if !seen.insert(account_id.clone()) {
                return Err(AccountsFormatError::new(
                    FORMAT_CHANGED,
                    "The same account is listed twice",
                ));
            }
            let label = text_of(&titles[0]["title"]);
            if label.is_empty() {
                return Err(AccountsFormatError::new(
                    FORMAT_CHANGED,
                    "An account row has an empty title",
                ));
            }
            accounts.push(ParsedAccount {
                id: account_id,
                name: title_case(&label),
                account_type: account_type(&category, &label),
                balance_eur: parse_amount(&amounts[0]["amount"])?,
            });
        }

        if !supported {
            skipped.push(if category.is_empty() {
                "unnamed".to_owned()
            } else {
                category
            });
        }
    }

    let mut categories = Vec::new();
    if found.contains(CHECKING_CATEGORY) {
        categories.push("CHECKING");
    }
    if found.contains(SAVINGS_CATEGORY) {
        categories.push("SAVINGS");
    }
    Ok((accounts, skipped, categories))
}
