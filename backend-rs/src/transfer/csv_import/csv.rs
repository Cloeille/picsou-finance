//! CSV reading for the import wizard: the RFC 4180 reader (CsvReader), dialect detection
//! (CsvDialectDetector) and cell parsing (CsvValueParser), with java.time date patterns.

use std::str::FromStr;
use std::sync::LazyLock;

use chrono::NaiveDate;
use regex::Regex;
use rust_decimal::Decimal;

use crate::ledger::sync_values::java_trim;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum DecimalStyle {
    Dot,
    Comma,
}

impl DecimalStyle {
    pub fn name(self) -> &'static str {
        match self {
            Self::Dot => "DOT",
            Self::Comma => "COMMA",
        }
    }
}

#[derive(Debug, Clone)]
pub struct CsvDialect {
    pub delimiter: char,
    pub decimal: DecimalStyle,
    pub date_format: DatePattern,
}

/// Parses content into rows; fully blank lines are skipped, a row of empty cells is kept.
pub fn parse(content: &str, delimiter: char) -> Vec<Vec<String>> {
    let mut rows = Vec::new();
    if content.is_empty() {
        return rows;
    }
    let content = content.strip_prefix('\u{FEFF}').unwrap_or(content);
    let chars: Vec<char> = content.chars().collect();
    let mut row = Vec::new();
    let mut field = String::new();
    let mut in_quotes = false;
    let mut i = 0;
    let n = chars.len();
    let add_row = |rows: &mut Vec<Vec<String>>, row: Vec<String>| {
        if !(row.len() == 1 && row[0].is_empty()) {
            rows.push(row);
        }
    };
    while i < n {
        let c = chars[i];
        if in_quotes {
            if c == '"' {
                if i + 1 < n && chars[i + 1] == '"' {
                    field.push('"');
                    i += 2;
                } else {
                    in_quotes = false;
                    i += 1;
                }
            } else {
                field.push(c);
                i += 1;
            }
        } else if c == '"' {
            in_quotes = true;
            i += 1;
        } else if c == delimiter {
            row.push(std::mem::take(&mut field));
            i += 1;
        } else if c == '\r' || c == '\n' {
            row.push(std::mem::take(&mut field));
            add_row(&mut rows, std::mem::take(&mut row));
            i += if c == '\r' && i + 1 < n && chars[i + 1] == '\n' {
                2
            } else {
                1
            };
        } else {
            field.push(c);
            i += 1;
        }
    }
    row.push(field);
    add_row(&mut rows, row);
    rows
}

const DELIMITER_CANDIDATES: [char; 3] = [';', ',', '\t'];
const DATE_PATTERNS: [&str; 6] = [
    "yyyy-MM-dd",
    "dd/MM/yyyy",
    "dd.MM.yyyy",
    "MM/dd/yyyy",
    "yyyy/MM/dd",
    "dd-MM-yyyy",
];
const SAMPLE_LIMIT: usize = 50;

static COMMA_DECIMAL: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r"^[+-]?\d{1,3}(\.\d{3})*,\d+$|^[+-]?\d+,\d+$").expect("a valid regex")
});
static DOT_DECIMAL: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r"^[+-]?\d{1,3}(,\d{3})*\.\d+$|^[+-]?\d+\.\d+$").expect("a valid regex")
});

/// The delimiter that appears most on the first line; comma by default.
pub fn detect_delimiter(content: &str) -> char {
    let first_line = content.split('\n').next().unwrap_or_default();
    let mut best = ',';
    let mut best_count = 0;
    for d in DELIMITER_CANDIDATES {
        let count = first_line.chars().filter(|c| *c == d).count();
        if count > best_count {
            best_count = count;
            best = d;
        }
    }
    best
}

pub fn detect_decimal(rows: &[Vec<String>]) -> DecimalStyle {
    let (mut comma, mut dot) = (0, 0);
    for row in rows.iter().take(SAMPLE_LIMIT) {
        for cell in row {
            let c = java_trim(cell);
            if COMMA_DECIMAL.is_match(c) {
                comma += 1;
            } else if DOT_DECIMAL.is_match(c) {
                dot += 1;
            }
        }
    }
    if comma > dot {
        DecimalStyle::Comma
    } else {
        DecimalStyle::Dot
    }
}

pub fn detect_date_format(rows: &[Vec<String>]) -> &'static str {
    let mut best = DATE_PATTERNS[0];
    let mut best_count = 0;
    for pattern in DATE_PATTERNS {
        let compiled = DatePattern::compile(pattern).expect("a valid built-in pattern");
        let count = rows
            .iter()
            .take(SAMPLE_LIMIT)
            .flatten()
            .map(|cell| java_trim(cell))
            .filter(|c| !c.is_empty() && compiled.parse(c).is_some())
            .count();
        if count > best_count {
            best_count = count;
            best = pattern;
        }
    }
    best
}

/// A non-blank cell that is not a number.
#[derive(Debug)]
pub struct NotANumber;

/// CsvValueParser.parseDecimal: None for a blank cell. Spaces (regular, no-break, narrow) and
/// currency symbols are dropped, thousands separators removed and the decimal point normalized.
pub fn parse_decimal(
    raw: Option<&str>,
    style: DecimalStyle,
) -> Result<Option<Decimal>, NotANumber> {
    let Some(raw) = raw else {
        return Ok(None);
    };
    let s = java_trim(raw);
    if s.is_empty() {
        return Ok(None);
    }
    let mut s: String = s
        .chars()
        .filter(|c| !matches!(c, ' ' | '\u{A0}' | '\u{202F}' | '€' | '$' | '£'))
        .collect();
    s = match style {
        DecimalStyle::Comma => s.replace('.', "").replace(',', "."),
        DecimalStyle::Dot => s.replace(',', ""),
    };
    let s = s.strip_prefix('+').unwrap_or(&s);
    java_big_decimal(s).map(Some).ok_or(NotANumber)
}

/// new BigDecimal(s): optional sign, digits with an optional point, optional exponent.
pub fn java_big_decimal(s: &str) -> Option<Decimal> {
    let (mantissa, exponent) = match s.find(['e', 'E']) {
        Some(i) => (&s[..i], Some(&s[i + 1..])),
        None => (s, None),
    };
    let digits = mantissa.strip_prefix(['+', '-']).unwrap_or(mantissa);
    let (int, frac) = digits.split_once('.').unwrap_or((digits, ""));
    if (int.is_empty() && frac.is_empty())
        || !int.bytes().all(|b| b.is_ascii_digit())
        || !frac.bytes().all(|b| b.is_ascii_digit())
    {
        return None;
    }
    let normalized = if digits.ends_with('.') {
        &mantissa[..mantissa.len() - 1]
    } else {
        mantissa
    };
    let mut value = if normalized.starts_with('.')
        || normalized.starts_with("-.")
        || normalized.starts_with("+.")
    {
        Decimal::from_str(&normalized.replacen('.', "0.", 1)).ok()?
    } else {
        Decimal::from_str(normalized).ok()?
    };
    if let Some(exp) = exponent {
        let body = exp.strip_prefix(['+', '-']).unwrap_or(exp);
        if body.is_empty() || !body.bytes().all(|b| b.is_ascii_digit()) {
            return None;
        }
        let e: i64 = exp.parse().ok()?;
        if e >= 0 {
            for _ in 0..e {
                value = value.checked_mul(Decimal::TEN)?;
            }
        } else {
            let scale = value.scale() as i64 - e;
            if scale > 28 {
                return None;
            }
            value.set_scale(u32::try_from(scale).ok()?).ok()?;
        }
    }
    Some(value)
}

#[derive(Debug, Clone, PartialEq, Eq)]
enum Token {
    Year {
        count: usize,
        reduced: bool,
    },
    Month(usize),
    Day(usize),
    Literal(String),
    /// A valid java.time letter this parser does not read: the pattern compiles, no cell parses.
    Unsupported,
}

/// A java.time DateTimeFormatter.ofPattern pattern, read as LocalDate.parse reads it with the
/// SMART resolver (a day past the month's end is clamped to it).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DatePattern {
    pub source: String,
    tokens: Vec<Token>,
}

const JAVA_PATTERN_LETTERS: &str = "GuyDMLdQqYwWEecFahKkHmsSAnNVvzOXxZpBg";
const MONTHS: [&str; 12] = [
    "January",
    "February",
    "March",
    "April",
    "May",
    "June",
    "July",
    "August",
    "September",
    "October",
    "November",
    "December",
];

impl DatePattern {
    /// None when ofPattern would throw IllegalArgumentException.
    pub fn compile(pattern: &str) -> Option<Self> {
        let chars: Vec<char> = pattern.chars().collect();
        let mut tokens = Vec::new();
        let mut i = 0;
        while i < chars.len() {
            let c = chars[i];
            if c.is_ascii_alphabetic() {
                let mut count = 1;
                while i + count < chars.len() && chars[i + count] == c {
                    count += 1;
                }
                i += count;
                if !JAVA_PATTERN_LETTERS.contains(c) {
                    return None;
                }
                tokens.push(match c {
                    'y' | 'u' => Token::Year {
                        count,
                        reduced: count == 2,
                    },
                    'M' | 'L' if count <= 4 => Token::Month(count),
                    'M' | 'L' if count == 5 => Token::Unsupported,
                    'd' if count <= 2 => Token::Day(count),
                    'M' | 'L' | 'd' => return None,
                    _ => Token::Unsupported,
                });
            } else if c == '\'' {
                let mut literal = String::new();
                i += 1;
                loop {
                    let next = *chars.get(i)?;
                    if next == '\'' {
                        if chars.get(i + 1) == Some(&'\'') {
                            literal.push('\'');
                            i += 2;
                            continue;
                        }
                        i += 1;
                        break;
                    }
                    literal.push(next);
                    i += 1;
                }
                if literal.is_empty() {
                    literal.push('\'');
                }
                tokens.push(Token::Literal(literal));
            } else if matches!(c, '{' | '}' | '#') {
                return None;
            } else if matches!(c, '[' | ']') {
                tokens.push(Token::Unsupported);
                i += 1;
            } else {
                tokens.push(Token::Literal(c.to_string()));
                i += 1;
            }
        }
        Some(Self {
            source: pattern.to_owned(),
            tokens,
        })
    }

    pub fn parse(&self, text: &str) -> Option<NaiveDate> {
        let chars: Vec<char> = text.chars().collect();
        let mut pos = 0;
        let (mut year, mut month, mut day) = (None, None, None);
        for (index, token) in self.tokens.iter().enumerate() {
            let next_is_number = matches!(
                self.tokens.get(index + 1),
                Some(Token::Year { .. } | Token::Day(_)) | Some(Token::Month(1 | 2))
            );
            match token {
                Token::Literal(lit) => {
                    for expected in lit.chars() {
                        if chars.get(pos) != Some(&expected) {
                            return None;
                        }
                        pos += 1;
                    }
                }
                Token::Unsupported => return None,
                Token::Year { count, reduced } => {
                    let (min, max) = match (*count, *reduced) {
                        (_, true) => (2, 2),
                        (1, _) => (1, 19),
                        (n, _) => (n, n),
                    };
                    let value = digits(&chars, &mut pos, min, max, next_is_number)?;
                    year = Some(if *reduced { 2000 + value } else { value });
                }
                Token::Month(count) if *count >= 3 => {
                    let rest: String = chars[pos..].iter().collect();
                    let (number, len) = MONTHS.iter().enumerate().find_map(|(i, name)| {
                        let label = if *count == 3 { &name[..3] } else { name };
                        rest.starts_with(label).then_some((i + 1, label.len()))
                    })?;
                    month = Some(number as i64);
                    pos += len;
                }
                Token::Month(count) => {
                    let (min, max) = if *count == 1 { (1, 19) } else { (2, 2) };
                    month = Some(digits(&chars, &mut pos, min, max, next_is_number)?);
                }
                Token::Day(count) => {
                    let (min, max) = if *count == 1 { (1, 19) } else { (2, 2) };
                    day = Some(digits(&chars, &mut pos, min, max, next_is_number)?);
                }
            }
        }
        if pos != chars.len() {
            return None;
        }
        let (year, month, day) = (year?, month?, day?);
        if !(1..=999_999_999).contains(&year)
            || !(1..=12).contains(&month)
            || !(1..=31).contains(&day)
        {
            return None;
        }
        let year = i32::try_from(year).ok()?;
        let month = u32::try_from(month).ok()?;
        let first = NaiveDate::from_ymd_opt(year, month, 1)?;
        let last = first
            .checked_add_months(chrono::Months::new(1))?
            .pred_opt()?
            .format("%d")
            .to_string()
            .parse::<i64>()
            .ok()?;
        NaiveDate::from_ymd_opt(year, month, u32::try_from(day.min(last)).ok()?)
    }
}

/// Reads min..=max ASCII digits; a variable-width field followed by another number keeps the
/// digits that field needs (adjacent value parsing), approximated by taking only min.
fn digits(
    chars: &[char],
    pos: &mut usize,
    min: usize,
    max: usize,
    next_is_number: bool,
) -> Option<i64> {
    let available = chars[*pos..]
        .iter()
        .take_while(|c| c.is_ascii_digit())
        .count();
    let take = if next_is_number && min != max {
        min
    } else {
        available.min(max)
    };
    if take < min || available < take {
        return None;
    }
    let text: String = chars[*pos..*pos + take].iter().collect();
    *pos += take;
    text.parse().ok()
}

/// A non-blank cell the date pattern does not read.
#[derive(Debug)]
pub struct InvalidDate;

/// CsvValueParser.parseDate: None for a blank cell, Err for one the pattern does not read.
pub fn parse_date(
    raw: Option<&str>,
    pattern: &DatePattern,
) -> Result<Option<NaiveDate>, InvalidDate> {
    let Some(raw) = raw else {
        return Ok(None);
    };
    let s = java_trim(raw);
    if s.is_empty() {
        return Ok(None);
    }
    pattern.parse(s).map(Some).ok_or(InvalidDate)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn reader_handles_quotes_bom_crlf_and_blank_lines() {
        let rows = parse("\u{FEFF}a;\"b;\"\"c\"\r\n\r\n;;\n\"x\ny\"", ';');
        assert_eq!(
            rows,
            vec![
                vec!["a".to_owned(), "b;\"c".to_owned()],
                vec![String::new(), String::new(), String::new()],
                vec!["x\ny".to_owned()],
            ]
        );
    }

    #[test]
    fn decimals_follow_the_style_and_keep_their_scale() {
        let p = |s, style| {
            parse_decimal(Some(s), style)
                .unwrap()
                .map(|d| d.to_string())
        };
        assert_eq!(p("1 234,50 €", DecimalStyle::Comma), Some("1234.50".into()));
        assert_eq!(p("1.234,5", DecimalStyle::Comma), Some("1234.5".into()));
        assert_eq!(p("+1,234.56", DecimalStyle::Dot), Some("1234.56".into()));
        assert_eq!(p("  ", DecimalStyle::Dot), None);
        assert_eq!(p("1e3", DecimalStyle::Dot), Some("1000".into()));
        assert!(parse_decimal(Some("abc"), DecimalStyle::Dot).is_err());
        assert!(parse_decimal(Some("-"), DecimalStyle::Dot).is_err());
    }

    #[test]
    fn date_patterns_parse_like_java_smart() {
        let p = |pattern: &str, s: &str| DatePattern::compile(pattern).unwrap().parse(s);
        assert_eq!(
            p("dd/MM/yyyy", "15/01/2020"),
            NaiveDate::from_ymd_opt(2020, 1, 15)
        );
        assert_eq!(
            p("dd/MM/yyyy", "31/02/2020"),
            NaiveDate::from_ymd_opt(2020, 2, 29)
        );
        assert_eq!(p("dd/MM/yyyy", "5/01/2020"), None);
        assert_eq!(
            p("d/M/yyyy", "5/1/2020"),
            NaiveDate::from_ymd_opt(2020, 1, 5)
        );
        assert_eq!(
            p("yyyyMMdd", "20200115"),
            NaiveDate::from_ymd_opt(2020, 1, 15)
        );
        assert_eq!(
            p("dd/MM/yy", "15/01/20"),
            NaiveDate::from_ymd_opt(2020, 1, 15)
        );
        assert_eq!(p("yyyy-MM-dd", "2020-13-01"), None);
        assert_eq!(p("MM/yyyy", "01/2020"), None);
        assert!(DatePattern::compile("bb").is_none());
        assert!(DatePattern::compile("ddd").is_none());
        assert!(DatePattern::compile("'open").is_none());
    }

    #[test]
    fn detects_the_french_dialect() {
        let content = "Date;Prix\n15/01/2020;412,50\n";
        assert_eq!(detect_delimiter(content), ';');
        let rows = parse(content, ';');
        assert_eq!(detect_decimal(&rows), DecimalStyle::Comma);
        assert_eq!(detect_date_format(&rows), "dd/MM/yyyy");
    }
}
