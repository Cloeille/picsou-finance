//! HTML entity unescaping, tag stripping, and French diacritic folding helpers.
//!
//! Kept dependency-free so `accounts_parser` and `bourso` do not add extra crates.

use std::sync::LazyLock;

use regex::Regex;
use unicode_normalization::UnicodeNormalization;

static TAG_RE: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"<[^>]+>").expect("tag regex"));

/// Decode HTML numeric (`&#...;`, `&#x...;`) and common named entities in a single pass.
///
/// Decodes once so double-escaped text like `&amp;lt;b&amp;gt;` becomes `&lt;b&gt;`,
/// matching Python's `html.unescape`.
pub fn html_unescape(input: &str) -> String {
    if !input.contains('&') {
        return input.to_owned();
    }
    let bytes = input.as_bytes();
    let mut out = String::with_capacity(input.len());
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i] == b'&'
            && let Some((decoded, consumed)) = decode_entity(&input[i..])
        {
            out.push_str(&decoded);
            i += consumed;
            continue;
        }
        let ch = input[i..].chars().next().expect("valid utf-8 boundary");
        out.push(ch);
        i += ch.len_utf8();
    }
    out
}

fn decode_entity(s: &str) -> Option<(String, usize)> {
    debug_assert!(s.starts_with('&'));
    // Cap scan length for an entity reference.
    let rest = &s[1..];
    let semi = rest.find(';')?;
    if semi == 0 || semi > 32 {
        return None;
    }
    let token = &rest[..semi];
    let consumed = semi + 2; // '&' + token + ';'
    if let Some(num) = token.strip_prefix('#') {
        let code = if let Some(hex) = num.strip_prefix('x').or_else(|| num.strip_prefix('X')) {
            if hex.is_empty() || !hex.bytes().all(|b| b.is_ascii_hexdigit()) {
                return None;
            }
            u32::from_str_radix(hex, 16).ok()?
        } else {
            if num.is_empty() || !num.bytes().all(|b| b.is_ascii_digit()) {
                return None;
            }
            num.parse::<u32>().ok()?
        };
        let ch = html_numeric_char(code)?;
        return Some((ch.to_string(), consumed));
    }
    let named = match token {
        "amp" | "AMP" => "&",
        "lt" | "LT" => "<",
        "gt" | "GT" => ">",
        "quot" | "QUOT" => "\"",
        "apos" => "'",
        "nbsp" => "\u{00A0}",
        "euro" => "€",
        "minus" => "\u{2212}",
        "ndash" => "\u{2013}",
        "mdash" => "\u{2014}",
        "lsquo" => "\u{2018}",
        "rsquo" => "\u{2019}",
        "ldquo" => "\u{201C}",
        "rdquo" => "\u{201D}",
        "laquo" => "«",
        "raquo" => "»",
        "hellip" => "…",
        "deg" => "°",
        "ordm" => "º",
        "copy" | "COPY" => "©",
        "reg" | "REG" => "®",
        "trade" => "™",
        "times" => "×",
        "divide" => "÷",
        "plusmn" => "±",
        "middot" => "·",
        "bull" => "•",
        "eacute" => "é",
        "Eacute" => "É",
        "egrave" => "è",
        "Egrave" => "È",
        "ecirc" => "ê",
        "Ecirc" => "Ê",
        "euml" => "ë",
        "Euml" => "Ë",
        "agrave" => "à",
        "Agrave" => "À",
        "acirc" => "â",
        "Acirc" => "Â",
        "auml" => "ä",
        "Auml" => "Ä",
        "aacute" => "á",
        "Aacute" => "Á",
        "ccedil" => "ç",
        "Ccedil" => "Ç",
        "icirc" => "î",
        "Icirc" => "Î",
        "iuml" => "ï",
        "Iuml" => "Ï",
        "igrave" => "ì",
        "Igrave" => "Ì",
        "iacute" => "í",
        "Iacute" => "Í",
        "ocirc" => "ô",
        "Ocirc" => "Ô",
        "ouml" => "ö",
        "Ouml" => "Ö",
        "ograve" => "ò",
        "Ograve" => "Ò",
        "oacute" => "ó",
        "Oacute" => "Ó",
        "oelig" => "œ",
        "OElig" => "Œ",
        "ugrave" => "ù",
        "Ugrave" => "Ù",
        "ucirc" => "û",
        "Ucirc" => "Û",
        "uuml" => "ü",
        "Uuml" => "Ü",
        "uacute" => "ú",
        "Uacute" => "Ú",
        "yuml" => "ÿ",
        "Yuml" => "Ÿ",
        "ntilde" => "ñ",
        "Ntilde" => "Ñ",
        "aelig" => "æ",
        "AElig" => "Æ",
        "szlig" => "ß",
        "thinsp" => "\u{2009}",
        "ensp" => "\u{2002}",
        "emsp" => "\u{2003}",
        _ => return None,
    };
    Some((named.to_owned(), consumed))
}

/// HTML5 replacement for Windows-1252 numeric character references in `0x80..=0x9F`.
fn html_numeric_char(code: u32) -> Option<char> {
    if code == 0 {
        return Some('\u{FFFD}');
    }
    let mapped = match code {
        0x80 => '\u{20AC}',
        0x82 => '\u{201A}',
        0x83 => '\u{0192}',
        0x84 => '\u{201E}',
        0x85 => '\u{2026}',
        0x86 => '\u{2020}',
        0x87 => '\u{2021}',
        0x88 => '\u{02C6}',
        0x89 => '\u{2030}',
        0x8A => '\u{0160}',
        0x8B => '\u{2039}',
        0x8C => '\u{0152}',
        0x8E => '\u{017D}',
        0x91 => '\u{2018}',
        0x92 => '\u{2019}',
        0x93 => '\u{201C}',
        0x94 => '\u{201D}',
        0x95 => '\u{2022}',
        0x96 => '\u{2013}',
        0x97 => '\u{2014}',
        0x98 => '\u{02DC}',
        0x99 => '\u{2122}',
        0x9A => '\u{0161}',
        0x9B => '\u{203A}',
        0x9C => '\u{0153}',
        0x9E => '\u{017E}',
        0x9F => '\u{0178}',
        _ => return char::from_u32(code),
    };
    Some(mapped)
}

/// Strip HTML tags, unescape entities once, and collapse runs of whitespace to single spaces.
pub fn plain_text(raw: &str) -> String {
    let without_tags = TAG_RE.replace_all(raw, " ");
    let unescaped = html_unescape(&without_tags);
    unescaped.split_whitespace().collect::<Vec<_>>().join(" ")
}

/// Strip HTML tags, unescape entities once, collapse whitespace, and truncate to `limit` chars.
pub fn clean_text(raw: &str, limit: usize) -> Option<String> {
    let cleaned = plain_text(raw);
    if cleaned.is_empty() {
        return None;
    }
    Some(cleaned.chars().take(limit).collect())
}

/// Strip combining diacritical marks (Unicode NFD, removing Mn category) and uppercase.
pub fn deaccent(value: &str) -> String {
    value
        .nfd()
        .filter(|ch| !is_combining_mark(*ch))
        .collect::<String>()
        .to_uppercase()
}

fn is_combining_mark(ch: char) -> bool {
    // Nonspacing marks (Mn) covering Latin/French diacritics and common combining blocks.
    matches!(
        ch as u32,
        0x0300..=0x036F
            | 0x1AB0..=0x1AFF
            | 0x1DC0..=0x1DFF
            | 0x20D0..=0x20FF
            | 0xFE20..=0xFE2F
    )
}
