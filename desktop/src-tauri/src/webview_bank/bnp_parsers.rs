//! Pure parsers for BNP Paribas / Hello bank! JSON (1:1 port of `services/bnp-auth/parsers.py`).
//!
//! No I/O and no browser here: `bnp.rs` fetches, these functions turn responses into the
//! normalized snapshot `backend-rs/src/connectors/bnp/sync.rs` expects. Anything unexpected
//! fails closed (`ParseError`, or `snapshotComplete: false`), using `rust_decimal::Decimal`
//! end to end with zero binary floats.

use std::collections::{HashMap, HashSet};
use std::str::FromStr;
use std::sync::LazyLock;

use chrono::NaiveDate;
use regex::Regex;
use rust_decimal::{Decimal, RoundingStrategy};
use serde::Serialize;
use serde_json::Value;
use sha2::{Digest, Sha256};

const DEFERRED_SUMMARY: &str = "FACTURE CARTE SELON RELEVE DU";
const SECURITIES_FAMILY: &str = "4";
const MAX_ACCOUNTS: usize = 200;
const MAX_OPERATIONS: usize = 2000;
const MAX_POSITIONS: usize = 500;

static IBAN_RE: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"^[A-Z]{2}\d{2}[A-Z0-9]{11,30}$").expect("iban regex"));
static ISIN_RE: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"^[A-Z]{2}[A-Z0-9]{9}\d$").expect("isin regex"));

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum EnvelopeError {
    SessionExpired,
    ParseError(&'static str),
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ParseError(pub &'static str);

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AccountRow {
    pub key: String,
    pub value: String,
    pub iban: Option<String>,
    pub family: String,
    pub label: String,
    pub currency: String,
    pub balance: Option<Decimal>,
    pub external_id: String,
    pub kind: &'static str,
    pub cash: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct UnsupportedProduct {
    pub external_id: String,
    pub family_code: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct NormalizedOperation {
    pub external_id: String,
    pub date: String,
    pub description: String,
    pub amount: String,
    pub counterparty: Option<String>,
    pub kind: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SynthesisAccount {
    pub number: String,
    pub name: String,
    pub value: Decimal,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct NormalizedPosition {
    pub isin: String,
    pub label: String,
    pub quantity: String,
    pub value: String,
    pub pnl: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct NormalizedAccount {
    pub external_id: String,
    pub kind: String,
    pub name: String,
    pub balance: String,
    pub cash_balance: Option<String>,
    pub currency: String,
    pub iban: Option<String>,
    pub transactions: Vec<NormalizedOperation>,
    pub positions: Vec<NormalizedPosition>,
    pub snapshot_complete: bool,
}

/// Parses JSON keeping decimal numbers exact (via `serde_json`'s `arbitrary_precision`).
pub fn loads(input: &str) -> Result<Value, ParseError> {
    serde_json::from_str(input).map_err(|_| ParseError("UPSTREAM_FORMAT_CHANGED"))
}

/// Extracts `codeRetour` as an integer from a `{codeRetour, message, data}` object.
fn code_retour(body: &Value) -> Option<i64> {
    let map = body.as_object()?;
    let raw = map.get("codeRetour")?;
    match raw {
        Value::Number(n) => n
            .as_i64()
            .or_else(|| n.to_string().trim().parse::<i64>().ok()),
        Value::String(s) => s.trim().parse::<i64>().ok(),
        _ => None,
    }
}

/// Returns `data` of a `{codeRetour, message, data}` answer; a dead session returns `SessionExpired`.
pub fn envelope(body: &Value) -> Result<Value, EnvelopeError> {
    let Some(code) = code_retour(body) else {
        return Err(EnvelopeError::ParseError("UPSTREAM_FORMAT_CHANGED"));
    };
    if code == -10 || code == -30 {
        return Err(EnvelopeError::SessionExpired);
    }
    if code != 0 {
        return Err(EnvelopeError::ParseError("UPSTREAM_UNAVAILABLE"));
    }
    Ok(body.get("data").cloned().unwrap_or(Value::Null))
}

/// Signed in: `InfosClient` answers `codeRetour` 0 AND carries a non-empty `data` value.
pub fn session_alive(info: &Value) -> bool {
    let Some(code) = code_retour(info) else {
        return false;
    };
    if code != 0 {
        return false;
    }
    match info.get("data") {
        None | Some(Value::Null) | Some(Value::Bool(false)) => false,
        Some(Value::String(s)) => !s.is_empty(),
        Some(Value::Array(a)) => !a.is_empty(),
        Some(Value::Object(m)) => !m.is_empty(),
        Some(Value::Number(n)) => n.as_i64() != Some(0),
        Some(Value::Bool(true)) => true,
    }
}

/// Parses a JSON number or numeric string as a `Decimal`; `None` when absent, boolean, or invalid.
pub fn money(value: Option<&Value>) -> Option<Decimal> {
    let value = value?;
    let raw = match value {
        Value::Null | Value::Bool(_) | Value::Array(_) | Value::Object(_) => return None,
        Value::Number(n) => n.to_string(),
        Value::String(s) => s.trim().replace(',', ".").replace(' ', ""),
    };
    let trimmed = raw.trim();
    if trimmed.is_empty() {
        return None;
    }
    Decimal::from_str(trimmed)
        .or_else(|_| Decimal::from_scientific(trimmed))
        .ok()
}

pub fn amount_text(value: Decimal) -> String {
    let rounded = value.round_dp_with_strategy(2, RoundingStrategy::MidpointNearestEven);
    format!("{rounded:.2}")
}

pub fn quantity_text(value: Decimal) -> String {
    let normalized = value.normalize();
    if normalized.is_zero() {
        "0".to_owned()
    } else {
        normalized.to_string()
    }
}

pub fn text(value: Option<&Value>, limit: usize) -> String {
    let raw = match value {
        None | Some(Value::Null) => return String::new(),
        Some(Value::String(s)) => s.clone(),
        Some(Value::Number(n)) => n.to_string(),
        Some(Value::Bool(b)) => b.to_string(),
        Some(other) => other.to_string(),
    };
    let collapsed = raw.split_whitespace().collect::<Vec<_>>().join(" ");
    collapsed.chars().take(limit).collect()
}

/// The account's `key` is an encrypted handle that may change per session: derive the id
/// from the IBAN or the masked number instead.
pub fn stable_id(iban: Option<&str>, value: &str) -> String {
    let basis = iban
        .map(str::trim)
        .filter(|s| !s.is_empty())
        .unwrap_or(value.trim());
    let collapsed = basis.split_whitespace().collect::<Vec<_>>().join(" ");
    if collapsed.is_empty() {
        return String::new();
    }
    let digest = hex::encode(Sha256::digest(collapsed.as_bytes()));
    digest[..20].to_owned()
}

/// Matches `(?<![a-z])needle(?![a-z])` on an already lower-cased string.
fn contains_word(haystack: &str, needle: &str) -> bool {
    let bytes = haystack.as_bytes();
    let mut start = 0;
    while let Some(pos) = haystack[start..].find(needle) {
        let abs = start + pos;
        let end = abs + needle.len();
        let before_ok = abs == 0 || !bytes[abs - 1].is_ascii_lowercase();
        let after_ok = end == bytes.len() || !bytes[end].is_ascii_lowercase();
        if before_ok && after_ok {
            return true;
        }
        start = abs + 1;
    }
    false
}

pub fn kind_of(family: &str, label: &str) -> Option<&'static str> {
    let base = match family {
        "1" => "CURRENT_ACCOUNT",
        "2" => "SAVINGS",
        "4" => "COMPTE_TITRES",
        "5" | "6" => "ASSURANCE_VIE",
        "8" | "9" => "LOAN",
        _ => return None,
    };
    let lowered = label.to_lowercase();
    let rules: &[(&str, &str)] = match family {
        "2" => &[
            ("livret a", "LIVRET_A"),
            ("developpement durable", "LDDS"),
            ("ldd", "LDDS"),
            ("epargne populaire", "LEP"),
            ("lep", "LEP"),
            ("livret jeune", "LIVRET_JEUNE"),
            ("compte epargne logement", "CEL"),
            ("cel", "CEL"),
            ("pel", "PEL"),
        ],
        "4" => &[("pea", "PEA")],
        "5" | "6" => &[("multiplacements", "PER"), ("per", "PER")],
        _ => &[],
    };
    for &(needle, kind) in rules {
        if contains_word(&lowered, needle) {
            return Some(kind);
        }
    }
    Some(base)
}

/// Maps `key` (`ibanCrypte`) -> valid-looking IBAN.
pub fn parse_ibans(data: &Value) -> HashMap<String, String> {
    let mut out = HashMap::new();
    let Some(list) = data.get("listeRib").and_then(Value::as_array) else {
        return out;
    };
    for item in list {
        let Some(info) = item.get("infoCompte").and_then(Value::as_object) else {
            continue;
        };
        let iban: String = info
            .get("iban")
            .and_then(Value::as_str)
            .unwrap_or("")
            .split_whitespace()
            .collect::<String>()
            .to_uppercase();
        let key = text(info.get("ibanCrypte"), 400);
        if !key.is_empty() && IBAN_RE.is_match(&iban) {
            out.insert(key, iban);
        }
    }
    out
}

/// Parses `(rows, unsupported)` from `getlstcpt`'s `data` block.
pub fn parse_accounts(
    data: &Value,
    ibans: &HashMap<String, String>,
) -> Result<(Vec<AccountRow>, Vec<UnsupportedProduct>), ParseError> {
    let Some(families) = data
        .get("infoUdc")
        .and_then(|v| v.get("familleCompte"))
        .and_then(Value::as_array)
    else {
        return Err(ParseError("UPSTREAM_FORMAT_CHANGED"));
    };
    if families.is_empty() {
        return Err(ParseError("UPSTREAM_FORMAT_CHANGED"));
    }

    let mut rows = Vec::new();
    let mut unsupported = Vec::new();
    for family in families {
        let family_id = text(family.get("idFamilleCompte"), 10);
        let Some(comptes) = family.get("compte").and_then(Value::as_array) else {
            continue;
        };
        for raw in comptes {
            let key = text(raw.get("key"), 400);
            let perso = text(raw.get("libellePersoProduit"), 120);
            let product = text(raw.get("libelleProduit"), 120);
            let label = if !perso.is_empty() {
                perso
            } else {
                product.clone()
            };
            let value = text(raw.get("value"), 60);
            let iban = ibans.get(&key).cloned();
            let currency_raw = text(raw.get("devise"), 3);
            let currency = if currency_raw.is_empty() {
                "EUR".to_owned()
            } else {
                currency_raw.to_uppercase()
            };
            let balance = money(raw.get("soldeDispo"));
            let external_id = stable_id(iban.as_deref(), &value);
            let kind_basis = if !product.is_empty() {
                product.to_lowercase()
            } else {
                label.to_lowercase()
            };
            let kind = kind_of(&family_id, &kind_basis);

            if key.is_empty() || external_id.is_empty() || kind.is_none() {
                if !external_id.is_empty() {
                    unsupported.push(UnsupportedProduct {
                        external_id,
                        family_code: family_id.chars().take(10).collect(),
                    });
                }
                continue;
            }
            let kind = kind.expect("checked above");
            if kind == "LOAN" && balance.is_none() {
                continue;
            }
            let cash = family_id == SECURITIES_FAMILY && product.to_lowercase().contains("especes");
            rows.push(AccountRow {
                key,
                value,
                iban,
                family: family_id.clone(),
                label,
                currency,
                balance,
                external_id,
                kind,
                cash,
            });
        }
    }
    if rows.len() + unsupported.len() > MAX_ACCOUNTS {
        return Err(ParseError("UPSTREAM_FORMAT_CHANGED"));
    }
    Ok((rows, unsupported))
}

pub fn french_date(value: Option<&Value>) -> Option<NaiveDate> {
    let raw = value?.as_str()?.trim();
    for pattern in ["%d-%m-%Y", "%d/%m/%Y", "%Y-%m-%d"] {
        if let Ok(date) = NaiveDate::parse_from_str(raw, pattern) {
            return Some(date);
        }
    }
    None
}

/// Parses past operations from `releveOp`'s `data` block; `None` when malformed.
pub fn parse_operations(data: &Value) -> Option<Vec<NormalizedOperation>> {
    let compte = data.get("listerOperations")?.get("compte")?.as_object()?;
    let past = match compte.get("operationPassee") {
        None | Some(Value::Null) => return Some(Vec::new()),
        Some(Value::Array(items)) => items,
        Some(_) => return None,
    };
    if past.len() > MAX_OPERATIONS {
        return None;
    }
    let mut out = Vec::with_capacity(past.len());
    let mut seen: HashMap<String, usize> = HashMap::new();
    let mut ids: HashSet<String> = HashSet::with_capacity(past.len());

    for item in past {
        let label = text(item.get("libelleOperation"), 300);
        let when = french_date(item.get("dateOperation"))?;
        let amount = money(item.get("montant").and_then(|m| m.get("montant")))?;
        if label.is_empty() {
            return None;
        }
        if label.to_uppercase().contains(DEFERRED_SUMMARY) {
            continue;
        }
        let mut basis = text(item.get("idOperation"), 100);
        let date_iso = when.format("%Y-%m-%d").to_string();
        let amount_str = amount_text(amount);
        if basis.is_empty() {
            let digest_key = format!("{date_iso}|{amount_str}|{label}");
            let count = seen.entry(digest_key.clone()).or_insert(0);
            *count += 1;
            let hashed = hex::encode(Sha256::digest(format!("{digest_key}|{count}").as_bytes()));
            basis = hashed[..24].to_owned();
        }
        if !ids.insert(basis.clone()) {
            return None;
        }
        let kind_raw = text(
            item.get("operationType")
                .and_then(|t| t.get("codeFamille"))
                .filter(|v| !v.is_null() && v.as_str() != Some(""))
                .or_else(|| item.get("categorie")),
            40,
        );
        out.push(NormalizedOperation {
            external_id: basis,
            date: date_iso,
            description: label,
            amount: amount_str,
            counterparty: None,
            kind: (!kind_raw.is_empty()).then_some(kind_raw),
        });
    }
    Some(out)
}

pub fn parse_synthesis(data: &Value) -> Vec<SynthesisAccount> {
    let mut out = Vec::new();
    let Some(items) = data.get("synSecurityAccounts").and_then(Value::as_array) else {
        return out;
    };
    for item in items {
        let number = text(item.get("securityAccountNumber"), 40);
        let Some(value) = money(item.get("valorisation")) else {
            continue;
        };
        if !number.is_empty() {
            out.push(SynthesisAccount {
                number,
                name: text(item.get("securityAccountName"), 120),
                value,
            });
        }
    }
    out
}

pub fn parse_positions(data: &Value) -> Option<Vec<NormalizedPosition>> {
    let raw = data.get("listofPortfolios")?.as_array()?;
    if raw.len() > MAX_POSITIONS {
        return None;
    }
    let mut lines = Vec::with_capacity(raw.len());
    for item in raw {
        let isin = text(item.get("securityCode"), 20).to_uppercase();
        let quantity = money(item.get("quantityOwned"))?;
        let value = money(item.get("valorizationValuation"))?;
        if !ISIN_RE.is_match(&isin) {
            return None;
        }
        let pnl = money(item.get("profitLossValorisation"));
        lines.push(NormalizedPosition {
            isin,
            label: text(item.get("securityName"), 120),
            quantity: quantity_text(quantity),
            value: amount_text(value),
            pnl: pnl.map(amount_text),
        });
    }
    Some(lines)
}

fn last_four_chars(s: &str) -> Option<String> {
    let chars: Vec<char> = s.chars().collect();
    if chars.len() < 4 {
        return None;
    }
    Some(chars[chars.len() - 4..].iter().collect())
}

/// The single securities account whose number ends like the account's masked number; ambiguity is `None`.
pub fn match_securities<'a>(
    row_value: &str,
    synthesis: &'a [SynthesisAccount],
) -> Option<&'a SynthesisAccount> {
    let tail = last_four_chars(row_value)?;
    let mut found = synthesis.iter().filter(|item| item.number.ends_with(&tail));
    let first = found.next()?;
    if found.next().is_some() {
        return None;
    }
    Some(first)
}

pub fn build_accounts(
    rows: &[AccountRow],
    histories: &HashMap<String, Option<Vec<NormalizedOperation>>>,
    synthesis: &[SynthesisAccount],
    portfolios: &HashMap<String, Option<Vec<NormalizedPosition>>>,
) -> Vec<NormalizedAccount> {
    let cash_rows: Vec<&AccountRow> = rows.iter().filter(|r| r.cash).collect();
    let mut out = Vec::new();
    for row in rows {
        if row.cash {
            continue;
        }
        let mut snapshot_complete = true;
        let mut balance = row.balance;
        let mut cash_balance: Option<String> = None;
        let mut transactions = Vec::new();
        let mut positions = Vec::new();

        if matches!(row.kind, "PEA" | "COMPTE_TITRES") {
            let matched = match_securities(&row.value, synthesis);
            let lines = portfolios.get(&row.external_id).and_then(|v| v.as_ref());
            let row_tail = last_four_chars(&row.value);
            let matching_cash: Vec<Decimal> = cash_rows
                .iter()
                .filter(|c| row_tail.is_some() && last_four_chars(&c.value) == row_tail)
                .filter_map(|c| c.balance)
                .collect();
            match (matched, lines) {
                (Some(m), Some(pos_lines)) if matching_cash.len() <= 1 => {
                    balance = Some(m.value);
                    positions = pos_lines.clone();
                    cash_balance = matching_cash.first().copied().map(amount_text);
                }
                (m_opt, _) => {
                    snapshot_complete = false;
                    if let Some(m) = m_opt {
                        balance = Some(m.value);
                    }
                }
            }
        } else if matches!(
            row.kind,
            "CURRENT_ACCOUNT"
                | "LIVRET_A"
                | "LDDS"
                | "LEP"
                | "LIVRET_JEUNE"
                | "PEL"
                | "CEL"
                | "SAVINGS"
        ) {
            match histories.get(&row.external_id).and_then(|v| v.as_ref()) {
                Some(ops) => transactions = ops.clone(),
                None => snapshot_complete = false,
            }
        }

        let final_balance = match balance {
            Some(b) => b,
            None => {
                snapshot_complete = false;
                Decimal::ZERO
            }
        };

        out.push(NormalizedAccount {
            external_id: row.external_id.clone(),
            kind: row.kind.to_owned(),
            name: if row.label.is_empty() {
                "BNP Paribas".to_owned()
            } else {
                row.label.clone()
            },
            balance: amount_text(final_balance),
            cash_balance,
            currency: row.currency.clone(),
            iban: row.iban.clone(),
            transactions,
            positions,
            snapshot_complete,
        });
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    const IBAN: &str = "FR7630006000011234567890189";

    fn account_json(key: &str, family: &str, product: &str, balance: Value, value: &str) -> Value {
        let _ = family;
        json!({
            "key": key,
            "value": value,
            "libelleProduit": product,
            "libellePersoProduit": "",
            "devise": "EUR",
            "soldeDispo": balance,
        })
    }

    fn accounts_payload(families: &[(&str, Vec<Value>)]) -> Value {
        let list: Vec<Value> = families
            .iter()
            .map(|(fid, rows)| json!({ "idFamilleCompte": fid, "compte": rows }))
            .collect();
        json!({ "infoUdc": { "familleCompte": list } })
    }

    fn op_payload(ops: Vec<Value>) -> Value {
        json!({ "listerOperations": { "compte": { "operationPassee": ops } } })
    }

    fn op_json(label: &str, amount: Value, date: &str, id: Option<&str>) -> Value {
        json!({
            "idOperation": id,
            "libelleOperation": label,
            "dateOperation": date,
            "montant": { "montant": amount },
            "operationType": { "codeFamille": "CB" },
        })
    }

    fn sample_row() -> AccountRow {
        AccountRow {
            key: "k".to_owned(),
            value: "0000 5678".to_owned(),
            iban: None,
            family: "4".to_owned(),
            label: "PEA".to_owned(),
            currency: "EUR".to_owned(),
            balance: Some(Decimal::from(10)),
            external_id: "e1".to_owned(),
            kind: "PEA",
            cash: false,
        }
    }

    #[test]
    fn envelope_zero_returns_data_and_dead_session_is_told_apart() {
        assert_eq!(
            envelope(&json!({"codeRetour": "0", "data": {"a": 1}})),
            Ok(json!({"a": 1}))
        );
        for body in [
            json!({"codeRetour": -30}),
            json!({"codeRetour": -10, "message": "Client non authentifie"}),
            json!({"codeRetour": "-10", "message": "user not connected"}),
        ] {
            assert_eq!(envelope(&body), Err(EnvelopeError::SessionExpired));
        }
    }

    #[test]
    fn envelope_anything_else_fails_closed() {
        for body in [
            json!([]),
            json!({}),
            json!({"codeRetour": "x"}),
            json!({"codeRetour": 5, "message": "boom"}),
        ] {
            assert!(matches!(envelope(&body), Err(EnvelopeError::ParseError(_))));
        }
    }

    #[test]
    fn session_alive_requires_code_zero_and_non_empty_data() {
        assert!(session_alive(
            &json!({"codeRetour": "0", "data": {"client": "ok"}})
        ));
        assert!(!session_alive(&json!({"codeRetour": "0", "data": null})));
        assert!(!session_alive(&json!({"codeRetour": "0", "data": {}})));
        assert!(!session_alive(
            &json!({"codeRetour": "-10", "data": {"a": 1}})
        ));
    }

    #[test]
    fn json_numbers_stay_exact() {
        let parsed = loads(r#"{"a": 1234.10}"#).unwrap();
        assert_eq!(
            money(parsed.get("a")),
            Some(Decimal::from_str("1234.10").unwrap())
        );
        assert!(loads("<html>").is_err());
    }

    #[test]
    fn families_and_labels_become_kinds_and_unknown_ones_are_unsupported() {
        let payload = accounts_payload(&[
            (
                "1",
                vec![account_json(
                    "k1",
                    "1",
                    "Compte de cheques",
                    json!("12.3"),
                    "1111 2222",
                )],
            ),
            (
                "2",
                vec![
                    account_json("k2", "2", "Livret A", json!(100), "3333"),
                    account_json("k3", "2", "Compte Epargne Logement", json!(5), "4444"),
                    account_json("k4", "2", "Epargne mystere", json!(5), "5555"),
                ],
            ),
            (
                "4",
                vec![
                    account_json("k5", "4", "PEA Bourse", json!(1), "6666"),
                    account_json("k6", "4", "Compte titres", json!(1), "7777"),
                ],
            ),
            (
                "5",
                vec![
                    account_json("k7", "5", "Contrat vie", json!(9), "8888"),
                    account_json("k8", "5", "Multiplacements PER", json!(9), "9999"),
                ],
            ),
            (
                "8",
                vec![account_json(
                    "k9",
                    "8",
                    "Credit immobilier",
                    json!(-500),
                    "1010",
                )],
            ),
            (
                "3",
                vec![account_json("k10", "3", "Depot a terme", json!(1), "1212")],
            ),
            (
                "7",
                vec![account_json("k11", "7", "Inconnu", json!(1), "1313")],
            ),
        ]);
        let parsed = loads(&payload.to_string()).unwrap();
        let (rows, unsupported) = parse_accounts(&parsed, &HashMap::new()).unwrap();
        let kinds: Vec<&str> = rows.iter().map(|r| r.kind).collect();
        assert_eq!(
            kinds,
            vec![
                "CURRENT_ACCOUNT",
                "LIVRET_A",
                "CEL",
                "SAVINGS",
                "PEA",
                "COMPTE_TITRES",
                "ASSURANCE_VIE",
                "PER",
                "LOAN",
            ]
        );
        let codes: HashSet<&str> = unsupported.iter().map(|u| u.family_code.as_str()).collect();
        assert_eq!(codes, HashSet::from(["3", "7"]));
        assert!(unsupported.iter().all(|u| u.external_id.len() == 20));
    }

    #[test]
    fn external_id_does_not_depend_on_session_key() {
        let a = &parse_accounts(
            &accounts_payload(&[(
                "1",
                vec![account_json(
                    "k-one",
                    "1",
                    "Compte",
                    json!(1),
                    "0000 1234 5678",
                )],
            )]),
            &HashMap::new(),
        )
        .unwrap()
        .0[0];
        let b = &parse_accounts(
            &accounts_payload(&[(
                "1",
                vec![account_json(
                    "k-two",
                    "1",
                    "Compte",
                    json!(1),
                    "0000 1234 5678",
                )],
            )]),
            &HashMap::new(),
        )
        .unwrap()
        .0[0];
        assert_eq!(a.external_id, b.external_id);
        assert!(!a.external_id.contains("k-one"));
    }

    #[test]
    fn closed_loan_and_keyless_row_are_skipped_and_empty_answer_fails() {
        let payload = accounts_payload(&[
            (
                "8",
                vec![account_json(
                    "k1",
                    "8",
                    "Credit immobilier",
                    Value::Null,
                    "0000 1234 5678",
                )],
            ),
            (
                "1",
                vec![account_json("", "1", "X", json!(1), "0000 1234 5678")],
            ),
        ]);
        assert!(parse_accounts(&payload, &HashMap::new())
            .unwrap()
            .0
            .is_empty());
        assert!(parse_accounts(&json!({"infoUdc": {}}), &HashMap::new()).is_err());
    }

    #[test]
    fn ibans_join_on_key_and_non_iban_is_dropped() {
        let ibans = parse_ibans(&json!({
            "listeRib": [
                {"infoCompte": {"ibanCrypte": "k1", "iban": "fr76 3000 6000 0112 3456 7890 189"}},
                {"infoCompte": {"ibanCrypte": "k2", "iban": "nope"}}
            ]
        }));
        assert_eq!(ibans, HashMap::from([("k1".to_owned(), IBAN.to_owned())]));
        let row = &parse_accounts(
            &accounts_payload(&[(
                "1",
                vec![account_json(
                    "k1",
                    "1",
                    "Compte",
                    json!(1),
                    "0000 1234 5678",
                )],
            )]),
            &ibans,
        )
        .unwrap()
        .0[0];
        assert_eq!(row.iban.as_deref(), Some(IBAN));
    }

    #[test]
    fn operations_are_normalized_and_deferred_card_summary_is_dropped() {
        let data = loads(
            &op_payload(vec![
                op_json(
                    "CARTE SYNTHETIQUE",
                    json!("-12.3"),
                    "05/01/2026",
                    Some("o1"),
                ),
                op_json(
                    "FACTURE CARTE SELON RELEVE DU 31/01",
                    json!(-50),
                    "05/01/2026",
                    Some("o2"),
                ),
            ])
            .to_string(),
        )
        .unwrap();
        let ops = parse_operations(&data).unwrap();
        assert_eq!(ops.len(), 1);
        assert_eq!(
            (
                ops[0].external_id.as_str(),
                ops[0].date.as_str(),
                ops[0].amount.as_str(),
                ops[0].kind.as_deref()
            ),
            ("o1", "2026-01-05", "-12.30", Some("CB"))
        );
    }

    #[test]
    fn dash_date_of_live_site_is_read_day_first() {
        let ops = parse_operations(&op_payload(vec![
            op_json(
                "CARTE SYNTHETIQUE",
                json!("-12.3"),
                "05-01-2026",
                Some("100000000000000001"),
            ),
            op_json(
                "CARTE SYNTHETIQUE",
                json!("-12.3"),
                "31-01-2026",
                Some("100000000000000002"),
            ),
        ]))
        .unwrap();
        let dates: Vec<&str> = ops.iter().map(|o| o.date.as_str()).collect();
        assert_eq!(dates, vec!["2026-01-05", "2026-01-31"]);
        assert!(parse_operations(&op_payload(vec![op_json(
            "CARTE",
            json!(-1),
            "31-02-2026",
            None
        )]))
        .is_none());
    }

    #[test]
    fn without_operation_id_two_identical_rows_get_distinct_stable_ids() {
        let first = parse_operations(&op_payload(vec![
            op_json("CARTE SYNTHETIQUE", json!("-12.3"), "05/01/2026", None),
            op_json("CARTE SYNTHETIQUE", json!("-12.3"), "05/01/2026", None),
        ]))
        .unwrap();
        let second = parse_operations(&op_payload(vec![
            op_json("CARTE SYNTHETIQUE", json!("-12.3"), "05/01/2026", None),
            op_json("CARTE SYNTHETIQUE", json!("-12.3"), "05/01/2026", None),
        ]))
        .unwrap();
        let unique: HashSet<&str> = first.iter().map(|o| o.external_id.as_str()).collect();
        assert_eq!(unique.len(), 2);
        assert_eq!(
            first.iter().map(|o| &o.external_id).collect::<Vec<_>>(),
            second.iter().map(|o| &o.external_id).collect::<Vec<_>>()
        );
    }

    #[test]
    fn unreadable_operation_row_makes_whole_history_unusable() {
        for bad in [
            op_json("CARTE", json!(-1), "31/02/2026", None),
            op_json("CARTE", Value::Null, "05/01/2026", None),
            op_json("", json!(-1), "05/01/2026", None),
            op_json("CARTE", json!(-1), "2026/01/05", None),
        ] {
            assert!(parse_operations(&op_payload(vec![bad])).is_none());
        }
        assert!(parse_operations(&json!({})).is_none());
        assert!(parse_operations(&op_payload(vec![
            op_json("CARTE", json!(-1), "05/01/2026", Some("x")),
            op_json("CARTE", json!(-1), "05/01/2026", Some("x")),
        ]))
        .is_none());
    }

    #[test]
    fn no_past_operation_is_empty_history() {
        assert_eq!(
            parse_operations(&json!({"listerOperations": {"compte": {}}})),
            Some(Vec::new())
        );
    }

    #[test]
    fn positions_are_read_and_non_isin_line_makes_portfolio_unusable() {
        let lines = parse_positions(&json!({
            "listofPortfolios": [{
                "securityCode": "FR0000000001",
                "securityName": "Action A",
                "quantityOwned": 10,
                "valorizationValuation": 1000,
                "profitLossValorisation": 100
            }]
        }))
        .unwrap();
        assert_eq!(
            lines,
            vec![NormalizedPosition {
                isin: "FR0000000001".to_owned(),
                label: "Action A".to_owned(),
                quantity: "10".to_owned(),
                value: "1000.00".to_owned(),
                pnl: Some("100.00".to_owned()),
            }]
        );
        assert!(parse_positions(&json!({
            "listofPortfolios": [{
                "securityCode": "CASH",
                "securityName": "Action A",
                "quantityOwned": 10,
                "valorizationValuation": 1000,
                "profitLossValorisation": 100
            }]
        }))
        .is_none());
        assert!(parse_positions(&json!({})).is_none());
    }

    #[test]
    fn matching_needs_exactly_one_securities_account() {
        let one = vec![
            SynthesisAccount {
                number: "XX5678".to_owned(),
                name: String::new(),
                value: Decimal::from(1),
            },
            SynthesisAccount {
                number: "XX1111".to_owned(),
                name: String::new(),
                value: Decimal::from(2),
            },
        ];
        assert_eq!(
            match_securities("0000 1234 5678", &one).unwrap().number,
            "XX5678"
        );
        let mut two = one.clone();
        two.push(SynthesisAccount {
            number: "YY5678".to_owned(),
            name: String::new(),
            value: Decimal::from(3),
        });
        assert!(match_securities("0000 1234 5678", &two).is_none());
        assert!(match_securities("12", &one).is_none());
    }

    #[test]
    fn pea_uses_provider_valuation_lines_and_cash_row() {
        let mut cash = sample_row();
        cash.external_id = "e2".to_owned();
        cash.cash = true;
        cash.balance = Some(Decimal::from_str("500.00").unwrap());
        cash.label = "PEA Especes".to_owned();

        let rows = vec![sample_row(), cash];
        let lines = vec![NormalizedPosition {
            isin: "FR0000000001".to_owned(),
            label: "A".to_owned(),
            quantity: "1".to_owned(),
            value: "1000.00".to_owned(),
            pnl: None,
        }];
        let synthesis = vec![SynthesisAccount {
            number: "XX5678".to_owned(),
            name: String::new(),
            value: Decimal::from_str("1500.00").unwrap(),
        }];
        let out = build_accounts(
            &rows,
            &HashMap::new(),
            &synthesis,
            &HashMap::from([("e1".to_owned(), Some(lines))]),
        );
        assert_eq!(out.len(), 1);
        assert_eq!(
            (
                out[0].balance.as_str(),
                out[0].cash_balance.as_deref(),
                out[0].snapshot_complete
            ),
            ("1500.00", Some("500.00"), true)
        );
    }

    #[test]
    fn pea_without_lines_or_with_ambiguous_match_is_incomplete() {
        let synthesis = vec![SynthesisAccount {
            number: "XX5678".to_owned(),
            name: String::new(),
            value: Decimal::from(7),
        }];
        assert!(
            !build_accounts(
                &[sample_row()],
                &HashMap::new(),
                &synthesis,
                &HashMap::from([("e1".to_owned(), None)])
            )[0]
            .snapshot_complete
        );
        assert!(
            !build_accounts(&[sample_row()], &HashMap::new(), &[], &HashMap::new())[0]
                .snapshot_complete
        );
    }

    #[test]
    fn cash_account_without_readable_history_is_incomplete_and_loan_keeps_sign() {
        let mut checking = sample_row();
        checking.kind = "CURRENT_ACCOUNT";
        checking.family = "1".to_owned();
        checking.external_id = "c1".to_owned();
        checking.balance = Some(Decimal::from_str("12.30").unwrap());

        let mut loan = sample_row();
        loan.kind = "LOAN";
        loan.family = "8".to_owned();
        loan.external_id = "l1".to_owned();
        loan.balance = Some(Decimal::from(-500));

        let out = build_accounts(
            &[checking.clone(), loan],
            &HashMap::from([("c1".to_owned(), None)]),
            &[],
            &HashMap::new(),
        );
        assert_eq!(
            out.iter()
                .map(|a| (a.kind.as_str(), a.balance.as_str(), a.snapshot_complete))
                .collect::<Vec<_>>(),
            vec![
                ("CURRENT_ACCOUNT", "12.30", false),
                ("LOAN", "-500.00", true)
            ]
        );
        let op = NormalizedOperation {
            external_id: "o".to_owned(),
            date: "2026-01-05".to_owned(),
            description: "CARTE".to_owned(),
            amount: "-1.00".to_owned(),
            counterparty: None,
            kind: None,
        };
        let ok = &build_accounts(
            &[checking],
            &HashMap::from([("c1".to_owned(), Some(vec![op.clone()]))]),
            &[],
            &HashMap::new(),
        )[0];
        assert!(ok.snapshot_complete);
        assert_eq!(ok.transactions, vec![op]);
    }

    #[test]
    fn missing_balance_is_never_a_zero_that_looks_real() {
        let mut loan = sample_row();
        loan.kind = "LOAN";
        loan.balance = None;
        assert!(
            !build_accounts(&[loan], &HashMap::new(), &[], &HashMap::new())[0].snapshot_complete
        );
    }
}
