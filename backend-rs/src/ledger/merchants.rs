//! Merchant label cleaning and the offline brand knowledge base (ports of the Java
//! MerchantNormalizer and MerchantKnowledgeBase).
//!
//! The Java patterns run without UNICODE_CHARACTER_CLASS or UNICODE_CASE, so \b, \d, \s and
//! case-insensitivity are ASCII-only there; every pattern below is compiled in (?-u) mode to keep
//! those semantics (the Rust defaults are Unicode-aware).

use std::collections::HashMap;
use std::sync::LazyLock;

use regex::Regex;

use crate::ledger::model::{AliasMatchType, MerchantAlias, MerchantBrand};

fn ascii_regex(pattern: &str) -> Regex {
    Regex::new(pattern).expect("valid merchant pattern")
}

/// Leading transaction-type noise common on French statements.
static LEADING_NOISE: LazyLock<Regex> = LazyLock::new(|| {
    ascii_regex(concat!(
        r"(?i-u)^(?:achat[ \t\n\x0B\f\r]+)?(?:cb|carte(?:[ \t\n\x0B\f\r]+bancaire)?|",
        r"paiement(?:[ \t\n\x0B\f\r]+(?:cb|par[ \t\n\x0B\f\r]+carte))?|",
        r"facture[ \t\n\x0B\f\r]+carte|",
        r"vir(?:ement)?(?:[ \t\n\x0B\f\r]+(?:sepa|instantane|recu|emis))?|",
        r"prlv(?:[ \t\n\x0B\f\r]+sepa)?|prelevement(?:[ \t\n\x0B\f\r]+sepa)?|",
        r"retrait(?:[ \t\n\x0B\f\r]+dab)?)\b[ .:*-]*",
    ))
});

/// "carte 1234", "cb no 5678", "card 9012".
static CARD_REF: LazyLock<Regex> = LazyLock::new(|| {
    ascii_regex(r"(?i-u)\b(?:carte|cb|card)[ \t\n\x0B\f\r]*n?o?\.?[ \t\n\x0B\f\r]*[0-9]{2,}\b")
});

/// Date fragments: 01/02, 01.02.23, 2023-01-02, 12-04.
static DATE_FRAGMENT: LazyLock<Regex> =
    LazyLock::new(|| ascii_regex(r"(?-u)\b[0-9]{1,4}[./-][0-9]{1,2}(?:[./-][0-9]{1,4})?\b"));

/// Runs of 3+ digits (card suffixes, store ids, reference numbers).
static LONG_DIGITS: LazyLock<Regex> = LazyLock::new(|| ascii_regex(r"(?-u)\b[0-9]{3,}\b"));

static PUNCT: LazyLock<Regex> = LazyLock::new(|| ascii_regex(r"[*#/\\|]+"));

/// Apostrophes are removed (not spaced) so MCDONALD'S becomes mcdonalds.
static APOSTROPHE: LazyLock<Regex> = LazyLock::new(|| ascii_regex("['\u{2019}`]"));

static COMBINING_MARKS: LazyLock<Regex> = LazyLock::new(|| ascii_regex(r"\p{M}+"));

/// Java's \s: ASCII whitespace only.
static MULTISPACE: LazyLock<Regex> = LazyLock::new(|| ascii_regex(r"[ \t\n\x0B\f\r]+"));

/// The knowledge-base token separator: Java's "[\\s.]+".
static TOKEN_SEPARATOR: LazyLock<Regex> = LazyLock::new(|| ascii_regex(r"[ \t\n\x0B\f\r.]+"));

/// Clean, display-ready merchant label from the raw bank fields, counterparty first.
///
/// Empty only when both inputs are blank; falls back to the trimmed original when stripping
/// removed everything.
pub fn normalize(counterparty: Option<&str>, description: Option<&str>) -> String {
    let base = match first_non_blank(counterparty, description) {
        Some(base) => base,
        None => return String::new(),
    };
    let s = strip_processor_prefix(java_trim(base));
    let s = APOSTROPHE.replace_all(s, "");
    let s = LEADING_NOISE.replace_all(&s, "");
    let s = CARD_REF.replace_all(&s, " ");
    let s = DATE_FRAGMENT.replace_all(&s, " ");
    let s = LONG_DIGITS.replace_all(&s, " ");
    let s = PUNCT.replace_all(&s, " ");
    let s = MULTISPACE.replace_all(&s, " ");
    let mut s = java_trim(&s).to_owned();
    if s.is_empty() {
        s = MULTISPACE.replace_all(java_trim(base), " ").into_owned();
    }
    title_case(&s)
}

/// Lower-cased, accent-stripped, apostrophe-free, whitespace-collapsed key for lookups.
pub fn match_key(label: &str) -> String {
    if label.is_empty() {
        return String::new();
    }
    let lower = label.to_lowercase();
    let lower = APOSTROPHE.replace_all(&lower, "");
    let folded: String = lower.chars().map(strip_accent).collect();
    let stripped = COMBINING_MARKS.replace_all(&folded, "");
    java_trim(&MULTISPACE.replace_all(&stripped, " ")).to_owned()
}

/// Base letter of a lower-case Latin-1 or Latin Extended-A letter whose NFD form is that
/// letter plus combining marks; any other character is returned unchanged.
///
/// Stands in for Java's Normalizer NFD (the crate has no Unicode normalization dependency).
/// Letters without a canonical decomposition (ß, æ, ø, đ, ł, œ, ı, ŀ, ŉ, ſ ...) stay as they
/// are, as in Java. Precomposed letters beyond Latin Extended-A (Vietnamese, Greek tonos ...)
/// are not folded here, where Java would fold them.
fn strip_accent(c: char) -> char {
    match c {
        'à' | 'á' | 'â' | 'ã' | 'ä' | 'å' | 'ā' | 'ă' | 'ą' => 'a',
        'ç' | 'ć' | 'ĉ' | 'ċ' | 'č' => 'c',
        'ď' => 'd',
        'è' | 'é' | 'ê' | 'ë' | 'ē' | 'ĕ' | 'ė' | 'ę' | 'ě' => 'e',
        'ĝ' | 'ğ' | 'ġ' | 'ģ' => 'g',
        'ĥ' => 'h',
        'ì' | 'í' | 'î' | 'ï' | 'ĩ' | 'ī' | 'ĭ' | 'į' => 'i',
        'ĵ' => 'j',
        'ķ' => 'k',
        'ĺ' | 'ļ' | 'ľ' => 'l',
        'ñ' | 'ń' | 'ņ' | 'ň' => 'n',
        'ò' | 'ó' | 'ô' | 'õ' | 'ö' | 'ō' | 'ŏ' | 'ő' => 'o',
        'ŕ' | 'ŗ' | 'ř' => 'r',
        'ś' | 'ŝ' | 'ş' | 'š' => 's',
        'ţ' | 'ť' => 't',
        'ù' | 'ú' | 'û' | 'ü' | 'ũ' | 'ū' | 'ŭ' | 'ů' | 'ű' | 'ų' => 'u',
        'ŵ' => 'w',
        'ý' | 'ÿ' | 'ŷ' => 'y',
        'ź' | 'ż' | 'ž' => 'z',
        other => other,
    }
}

/// Payment processors wrap the merchant as "<PROCESSOR> *<MERCHANT>": keep what follows the
/// last '*' when something does.
fn strip_processor_prefix(s: &str) -> &str {
    match s.rfind('*') {
        Some(star) if star < s.len() - 1 => java_trim(&s[star + 1..]),
        _ => s,
    }
}

/// Capitalizes each space-separated word's first letter and lower-cases the rest.
fn title_case(s: &str) -> String {
    let lower = s.to_lowercase();
    let mut out = String::with_capacity(lower.len());
    for word in lower.split(' ').filter(|w| !w.is_empty()) {
        if !out.is_empty() {
            out.push(' ');
        }
        let mut chars = word.chars();
        if let Some(first) = chars.next() {
            out.push(java_upper_char(first));
            out.push_str(chars.as_str());
        }
    }
    out
}

/// Java's Character.toUpperCase(char): a single-char mapping, so ß stays ß, and a
/// supplementary character (seen by charAt as a lone surrogate) is left as is.
fn java_upper_char(c: char) -> char {
    if u32::from(c) > 0xFFFF {
        return c;
    }
    let mut upper = c.to_uppercase();
    match (upper.next(), upper.next()) {
        (Some(u), None) => u,
        _ => c,
    }
}

/// Java's String.trim(): strips every char up to U+0020 at both ends.
fn java_trim(s: &str) -> &str {
    s.trim_matches(|c: char| c <= ' ')
}

/// Java's Character.isWhitespace, which String.isBlank uses: no-break spaces and U+0085 are
/// not whitespace there, the information separators U+001C..U+001F are.
fn java_is_whitespace(c: char) -> bool {
    matches!(c, '\u{1C}'..='\u{1F}')
        || (c.is_whitespace() && !matches!(c, '\u{85}' | '\u{A0}' | '\u{2007}' | '\u{202F}'))
}

fn first_non_blank<'a>(a: Option<&'a str>, b: Option<&'a str>) -> Option<&'a str> {
    [a, b]
        .into_iter()
        .flatten()
        .find(|s| !s.chars().all(java_is_whitespace))
}

/// In-memory brand lookup over the merchant_brand and merchant_alias rows.
///
/// Java loads it once at startup into an immutable snapshot (reload swaps it wholesale); here
/// it is an immutable value built once and shared, rebuilt by loading again.
#[derive(Debug, Clone, Default)]
pub struct KnowledgeBase {
    by_id: HashMap<i64, MerchantBrand>,
    by_word: HashMap<String, i64>,
    /// Longest pattern first, so the most specific phrase wins.
    phrases: Vec<(String, i64)>,
}

impl KnowledgeBase {
    /// Loads every brand and alias row.
    pub async fn load<'c, E>(db: E) -> Result<KnowledgeBase, sqlx::Error>
    where
        E: crate::db::DbExec<'c> + Copy,
    {
        let brands = crate::db::query_as::<MerchantBrand>(
            "SELECT id, slug, display_name, default_category_slug, color, monogram, logo_domain \
             FROM merchant_brand ORDER BY id",
        )
        .fetch_all(db)
        .await?;
        let aliases = crate::db::query_as::<MerchantAlias>(
            "SELECT id, brand_id, pattern, match_type FROM merchant_alias ORDER BY id",
        )
        .fetch_all(db)
        .await?;
        Ok(KnowledgeBase::new(brands, aliases))
    }

    /// Loads every brand and alias row on an existing connection/transaction handle.
    pub async fn load_on(conn: &mut crate::db::DbHandle<'_>) -> Result<KnowledgeBase, sqlx::Error> {
        let brands = crate::db::query_as::<MerchantBrand>(
            "SELECT id, slug, display_name, default_category_slug, color, monogram, logo_domain \
             FROM merchant_brand ORDER BY id",
        )
        .fetch_all(&mut *conn)
        .await?;
        let aliases = crate::db::query_as::<MerchantAlias>(
            "SELECT id, brand_id, pattern, match_type FROM merchant_alias ORDER BY id",
        )
        .fetch_all(&mut *conn)
        .await?;
        Ok(KnowledgeBase::new(brands, aliases))
    }

    /// Builds the knowledge base from rows; orphan aliases are skipped and the first WORD
    /// alias of a pattern wins.
    pub fn new(brands: Vec<MerchantBrand>, aliases: Vec<MerchantAlias>) -> KnowledgeBase {
        let by_id: HashMap<i64, MerchantBrand> = brands.into_iter().map(|b| (b.id, b)).collect();
        let mut by_word = HashMap::new();
        let mut phrases = Vec::new();
        for alias in aliases {
            if !by_id.contains_key(&alias.brand_id) {
                continue;
            }
            match alias.match_type {
                AliasMatchType::Phrase => phrases.push((alias.pattern, alias.brand_id)),
                AliasMatchType::Word => {
                    by_word.entry(alias.pattern).or_insert(alias.brand_id);
                }
            }
        }
        // Java compares String.length(), which counts UTF-16 units; the sort is stable in both.
        phrases.sort_by_key(|(pattern, _)| std::cmp::Reverse(pattern.encode_utf16().count()));
        KnowledgeBase {
            by_id,
            by_word,
            phrases,
        }
    }

    /// Resolves a match key (see match_key) to a brand: PHRASE aliases first, longest first,
    /// then WORD aliases against each token split on whitespace and dots.
    pub fn find_match(&self, match_key: &str) -> Option<&MerchantBrand> {
        if match_key.chars().all(java_is_whitespace) {
            return None;
        }
        if let Some((_, id)) = self
            .phrases
            .iter()
            .find(|(pattern, _)| contains_word(match_key, pattern))
        {
            return self.by_id.get(id);
        }
        let mut tokens: Vec<&str> = TOKEN_SEPARATOR.split(match_key).collect();
        // String.split drops trailing empty strings (a leading one is kept).
        while tokens.last().is_some_and(|t| t.is_empty()) {
            tokens.pop();
        }
        tokens
            .into_iter()
            .find_map(|token| self.by_word.get(token))
            .and_then(|id| self.by_id.get(id))
    }

    /// The brand with this primary key, if loaded.
    pub fn find_by_id(&self, id: i64) -> Option<&MerchantBrand> {
        self.by_id.get(&id)
    }

    /// Number of brands loaded.
    pub fn brand_count(&self) -> usize {
        self.by_id.len()
    }
}

/// True when needle occurs in haystack delimited by spaces or the string ends, so "paul" does
/// not match "paula".
fn contains_word(haystack: &str, needle: &str) -> bool {
    let bytes = haystack.as_bytes();
    let mut from = 0;
    while let Some(offset) = haystack[from..].find(needle) {
        let idx = from + offset;
        let end = idx + needle.len();
        let left_ok = idx == 0 || bytes[idx - 1] == b' ';
        let right_ok = end == haystack.len() || bytes[end] == b' ';
        if left_ok && right_ok {
            return true;
        }
        match haystack[idx..].chars().next() {
            Some(c) => from = idx + c.len_utf8(),
            None => return false,
        }
    }
    false
}
