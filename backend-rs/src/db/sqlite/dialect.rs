//! Deterministic, quote- and comment-aware PostgreSQL -> SQLite SQL dialect translator.
//!
//! Translates standard Picsou PostgreSQL queries into SQLite-compatible SQL while strictly
//! leaving single-quoted string literals (`'...'`), double-quoted identifiers (`"..."`), line
//! comments (`-- ...`), and block comments (`/* ... */`) untouched.
//!
//! Supported transformations (outside literals & comments):
//! - Numbered placeholders: `$1`, `$2`, ... -> `?1`, `?2`, ...
//! - PostgreSQL type casts: `::text`, `::varchar`, `::bigint`, `::int`, `::integer`, `::int2`,
//!   `::int4`, `::int8`, `::smallint`, `::numeric`, `::decimal`, `::timestamptz`, `::timestamp`,
//!   `::date`, `::uuid`, `::boolean`, `::bool`, `::jsonb`, `::json`, `::bytea`, Picsou enums
//!   (`::account_type`, `::category_kind`, `::rate_basis`, `::recurring_cadence`,
//!   `::recurring_status`, `::requisition_status`, `::savings_product`, `::sharing_level`),
//!   optional `(p, s)` modifiers, and array suffixes (`[]`) -> stripped.
//! - Array membership:
//!   - `= ANY($n)` / `= ANY(?n)` -> `IN (SELECT value FROM json_each(?n))`
//!   - `!= ALL($n)` / `<> ALL($n)` -> `NOT IN (SELECT value FROM json_each(?n))`
//! - Row-level locks: `FOR UPDATE`, `FOR NO KEY UPDATE`, `FOR SHARE`, `FOR KEY SHARE`
//!   (including optional `NOWAIT` / `SKIP LOCKED`) -> stripped (`BEGIN IMMEDIATE` serializes writers).
//! - UTC timezone conversion: `AT TIME ZONE 'UTC'` -> stripped (SQLite stores RFC 3339 UTC text).
//! - Functions: `NOW()` -> `now()`, `LEAST(` -> `pg_least(`, `GREATEST(` -> `pg_greatest(`
//!   (registered UDFs that match PostgreSQL's NULL-ignoring and exact decimal comparison semantics).
//! - Typed ANSI date literals: `DATE 'YYYY-MM-DD'` -> `'YYYY-MM-DD'`.

use std::collections::HashMap;
use std::sync::{Arc, OnceLock, RwLock};

/// Upper bound on cached translated SQL statements so dynamic queries never grow unboundedly.
const MAX_CACHE_ENTRIES: usize = 2048;

static TRANSLATION_CACHE: OnceLock<RwLock<HashMap<String, Arc<str>>>> = OnceLock::new();

/// Translates a PostgreSQL SQL statement into SQLite SQL, memoizing the result for repeated queries.
pub fn translate_cached(sql: &str) -> Arc<str> {
    let cache = TRANSLATION_CACHE.get_or_init(|| RwLock::new(HashMap::new()));
    if let Ok(guard) = cache.read()
        && let Some(hit) = guard.get(sql)
    {
        return Arc::clone(hit);
    }

    let translated: Arc<str> = Arc::from(translate_pg_to_sqlite(sql));
    if let Ok(mut guard) = cache.write() {
        if guard.len() >= MAX_CACHE_ENTRIES {
            guard.clear();
        }
        guard.insert(sql.to_owned(), Arc::clone(&translated));
    }
    translated
}

/// Returns `true` only when `sql` is guaranteed to be a pure read-only query that can safely run
/// on a SQLite WAL read-only connection.
///
/// Conservative by design: write statements, DML CTEs (`WITH ... INSERT/UPDATE/DELETE/REPLACE`),
/// and `PRAGMA`s are routed to the writer connection; read-only `WITH` CTEs are routed to readers.
pub fn is_read_only_sql(sql: &str) -> bool {
    let bytes = sql.as_bytes();
    let mut i = 0usize;
    loop {
        while i < bytes.len() && bytes[i].is_ascii_whitespace() {
            i += 1;
        }
        if i + 1 < bytes.len() && bytes[i] == b'-' && bytes[i + 1] == b'-' {
            i += 2;
            while i < bytes.len() && bytes[i] != b'\n' {
                i += 1;
            }
            continue;
        }
        if i + 1 < bytes.len() && bytes[i] == b'/' && bytes[i + 1] == b'*' {
            i += 2;
            while i + 1 < bytes.len() && !(bytes[i] == b'*' && bytes[i + 1] == b'/') {
                i += 1;
            }
            i = (i + 2).min(bytes.len());
            continue;
        }
        break;
    }

    let rest = &sql[i..];
    let first_word_len = rest.bytes().take_while(|b| b.is_ascii_alphabetic()).count();
    let first_word = &rest[..first_word_len];
    if first_word.eq_ignore_ascii_case("SELECT")
        || first_word.eq_ignore_ascii_case("VALUES")
        || first_word.eq_ignore_ascii_case("EXPLAIN")
    {
        return true;
    }
    if first_word.eq_ignore_ascii_case("WITH") {
        let mut has_read = false;
        for token in tokenize(sql) {
            if let Token::Word(word) = token {
                if word.eq_ignore_ascii_case("INSERT")
                    || word.eq_ignore_ascii_case("UPDATE")
                    || word.eq_ignore_ascii_case("DELETE")
                    || word.eq_ignore_ascii_case("REPLACE")
                {
                    return false;
                }
                if word.eq_ignore_ascii_case("SELECT") || word.eq_ignore_ascii_case("VALUES") {
                    has_read = true;
                }
            }
        }
        return has_read;
    }
    false
}

/// Token stream emitted by the quote- and comment-aware SQL lexer.
#[derive(Debug, Clone, PartialEq, Eq)]
enum Token {
    /// Single-quoted literal, double-quoted identifier, or comment — copied verbatim.
    Verbatim(String),
    /// Whitespace run.
    Space(String),
    /// Unquoted identifier or keyword.
    Word(String),
    /// Numbered placeholder (`$1` or `?1`) storing the 1-based parameter number string.
    Placeholder(String),
    /// Punctuation or operator symbol.
    Symbol(String),
}

impl Token {
    fn is_space(&self) -> bool {
        matches!(self, Self::Space(_))
    }

    fn word_eq(&self, kw: &str) -> bool {
        matches!(self, Self::Word(w) if w.eq_ignore_ascii_case(kw))
    }

    fn sym_eq(&self, s: &str) -> bool {
        matches!(self, Self::Symbol(v) if v == s)
    }

    fn as_str(&self) -> &str {
        match self {
            Self::Verbatim(s) | Self::Space(s) | Self::Word(s) | Self::Symbol(s) => s,
            Self::Placeholder(n) => n,
        }
    }
}

fn tokenize(sql: &str) -> Vec<Token> {
    let bytes = sql.as_bytes();
    let n = bytes.len();
    let mut i = 0usize;
    let mut tokens = Vec::with_capacity(n / 4 + 8);

    while i < n {
        let b = bytes[i];

        // Whitespace
        if b.is_ascii_whitespace() {
            let start = i;
            while i < n && bytes[i].is_ascii_whitespace() {
                i += 1;
            }
            tokens.push(Token::Space(sql[start..i].to_owned()));
            continue;
        }

        // Line comment: -- ... \n
        if b == b'-' && i + 1 < n && bytes[i + 1] == b'-' {
            let start = i;
            i += 2;
            while i < n && bytes[i] != b'\n' {
                i += 1;
            }
            tokens.push(Token::Verbatim(sql[start..i].to_owned()));
            continue;
        }

        // Block comment: /* ... */
        if b == b'/' && i + 1 < n && bytes[i + 1] == b'*' {
            let start = i;
            i += 2;
            let mut depth = 1usize;
            while i < n && depth > 0 {
                if i + 1 < n && bytes[i] == b'/' && bytes[i + 1] == b'*' {
                    depth += 1;
                    i += 2;
                } else if i + 1 < n && bytes[i] == b'*' && bytes[i + 1] == b'/' {
                    depth -= 1;
                    i += 2;
                } else {
                    i += 1;
                }
            }
            tokens.push(Token::Verbatim(sql[start..i].to_owned()));
            continue;
        }

        // Single-quoted string literal: '...' (with '' escaping)
        if b == b'\'' {
            let start = i;
            i += 1;
            while i < n {
                if bytes[i] == b'\'' {
                    i += 1;
                    if i < n && bytes[i] == b'\'' {
                        i += 1;
                    } else {
                        break;
                    }
                } else {
                    i += 1;
                }
            }
            tokens.push(Token::Verbatim(sql[start..i].to_owned()));
            continue;
        }

        // Double-quoted identifier: "..." (with "" escaping)
        if b == b'"' {
            let start = i;
            i += 1;
            while i < n {
                if bytes[i] == b'"' {
                    i += 1;
                    if i < n && bytes[i] == b'"' {
                        i += 1;
                    } else {
                        break;
                    }
                } else {
                    i += 1;
                }
            }
            tokens.push(Token::Verbatim(sql[start..i].to_owned()));
            continue;
        }

        // PostgreSQL or SQLite numbered placeholder: $123 or ?123
        if (b == b'$' || b == b'?') && i + 1 < n && bytes[i + 1].is_ascii_digit() {
            let start = i + 1;
            i += 1;
            while i < n && bytes[i].is_ascii_digit() {
                i += 1;
            }
            tokens.push(Token::Placeholder(sql[start..i].to_owned()));
            continue;
        }

        // Unquoted word (identifier / keyword)
        if b.is_ascii_alphabetic() || b == b'_' {
            let start = i;
            i += 1;
            while i < n && (bytes[i].is_ascii_alphanumeric() || bytes[i] == b'_') {
                i += 1;
            }
            tokens.push(Token::Word(sql[start..i].to_owned()));
            continue;
        }

        // Numeric literal (kept as Verbatim so e.g. 1e5 or 123 stays intact)
        if b.is_ascii_digit() {
            let start = i;
            i += 1;
            while i < n && (bytes[i].is_ascii_alphanumeric() || bytes[i] == b'.') {
                i += 1;
            }
            tokens.push(Token::Verbatim(sql[start..i].to_owned()));
            continue;
        }

        // Multi-char operators: ::, !=, <>, <=, >=, ||
        if i + 1 < n {
            let pair = &sql[i..i + 2];
            if matches!(pair, "::" | "!=" | "<>" | "<=" | ">=" | "||") {
                tokens.push(Token::Symbol(pair.to_owned()));
                i += 2;
                continue;
            }
        }

        // Single-char symbol (respect UTF-8 char boundaries)
        let ch = sql[i..].chars().next().expect("non-empty slice");
        let len = ch.len_utf8();
        tokens.push(Token::Symbol(sql[i..i + len].to_owned()));
        i += len;
    }

    tokens
}

fn prev_non_space(tokens: &[Token], mut idx: usize) -> Option<usize> {
    while idx > 0 {
        idx -= 1;
        if !tokens[idx].is_space() {
            return Some(idx);
        }
    }
    None
}

fn next_non_space(tokens: &[Token], mut idx: usize) -> Option<usize> {
    while idx < tokens.len() {
        if !tokens[idx].is_space() {
            return Some(idx);
        }
        idx += 1;
    }
    None
}

fn is_known_pg_cast_type(word: &str) -> bool {
    matches!(
        word.to_ascii_lowercase().as_str(),
        "text"
            | "varchar"
            | "char"
            | "bpchar"
            | "character"
            | "bigint"
            | "int"
            | "integer"
            | "int2"
            | "int4"
            | "int8"
            | "smallint"
            | "numeric"
            | "decimal"
            | "timestamptz"
            | "timestamp"
            | "date"
            | "time"
            | "uuid"
            | "boolean"
            | "bool"
            | "jsonb"
            | "json"
            | "bytea"
            | "account_type"
            | "category_kind"
            | "rate_basis"
            | "recurring_cadence"
            | "recurring_status"
            | "requisition_status"
            | "savings_product"
            | "sharing_level"
    )
}

/// Attempts to consume a PostgreSQL `::type[(p[, s])][[]]` cast starting at `idx` (pointing at `::`).
/// Returns the index immediately after the cast if matched.
fn consume_pg_cast(tokens: &[Token], idx: usize) -> Option<usize> {
    if !tokens[idx].sym_eq("::") {
        return None;
    }
    let type_idx = next_non_space(tokens, idx + 1)?;
    let Token::Word(type_word) = &tokens[type_idx] else {
        return None;
    };
    if !is_known_pg_cast_type(type_word) {
        return None;
    }

    let mut cur = type_idx + 1;

    // Multi-word SQL standard type names: `character varying`, `timestamp with[out] time zone`
    if type_word.eq_ignore_ascii_case("character") {
        if let Some(next) = next_non_space(tokens, cur)
            && tokens[next].word_eq("varying")
        {
            cur = next + 1;
        }
    } else if (type_word.eq_ignore_ascii_case("timestamp")
        || type_word.eq_ignore_ascii_case("time"))
        && let Some(w1) = next_non_space(tokens, cur)
        && (tokens[w1].word_eq("with") || tokens[w1].word_eq("without"))
        && let Some(w2) = next_non_space(tokens, w1 + 1)
        && tokens[w2].word_eq("time")
        && let Some(w3) = next_non_space(tokens, w2 + 1)
        && tokens[w3].word_eq("zone")
    {
        cur = w3 + 1;
    }

    // Optional precision/scale modifier: `(19, 8)` or `(255)`
    if let Some(open) = next_non_space(tokens, cur)
        && tokens[open].sym_eq("(")
    {
        let mut p = open + 1;
        let mut depth = 1usize;
        while p < tokens.len() && depth > 0 {
            if tokens[p].sym_eq("(") {
                depth += 1;
            } else if tokens[p].sym_eq(")") {
                depth -= 1;
            }
            p += 1;
        }
        if depth == 0 {
            cur = p;
        }
    }

    // Optional array suffix: `[]` (possibly multiple dimensions)
    loop {
        if let Some(open) = next_non_space(tokens, cur)
            && tokens[open].sym_eq("[")
            && let Some(close) = next_non_space(tokens, open + 1)
            && tokens[close].sym_eq("]")
        {
            cur = close + 1;
        } else {
            break;
        }
    }

    Some(cur)
}

/// Attempts to consume `= ANY(?n)` or `!= ALL(?n)` / `<> ALL(?n)` starting at `idx`.
/// Returns `(replacement, next_idx)`.
fn consume_any_or_all(tokens: &[Token], idx: usize) -> Option<(String, usize)> {
    let is_eq = tokens[idx].sym_eq("=");
    let is_neq = tokens[idx].sym_eq("!=") || tokens[idx].sym_eq("<>");
    if !is_eq && !is_neq {
        return None;
    }
    let kw_idx = next_non_space(tokens, idx + 1)?;
    let matched_any = is_eq && tokens[kw_idx].word_eq("ANY");
    let matched_all = is_neq && tokens[kw_idx].word_eq("ALL");
    if !matched_any && !matched_all {
        return None;
    }

    let open_idx = next_non_space(tokens, kw_idx + 1)?;
    if !tokens[open_idx].sym_eq("(") {
        return None;
    }
    let arg_idx = next_non_space(tokens, open_idx + 1)?;
    let Token::Placeholder(param_num) = &tokens[arg_idx] else {
        return None;
    };

    // Allow optional `::type[]` cast inside `ANY($1::bigint[])`
    let mut after_arg = arg_idx + 1;
    while let Some(cast_pos) = next_non_space(tokens, after_arg) {
        if let Some(after_cast) = consume_pg_cast(tokens, cast_pos) {
            after_arg = after_cast;
        } else {
            break;
        }
    }

    let close_idx = next_non_space(tokens, after_arg)?;
    if !tokens[close_idx].sym_eq(")") {
        return None;
    }

    let replacement = if matched_any {
        format!("IN (SELECT value FROM json_each(?{param_num}))")
    } else {
        format!("NOT IN (SELECT value FROM json_each(?{param_num}))")
    };
    Some((replacement, close_idx + 1))
}

/// Attempts to consume PostgreSQL row-level locking clauses:
/// `FOR UPDATE`, `FOR NO KEY UPDATE`, `FOR SHARE`, `FOR KEY SHARE`
/// (with optional `OF ident[, ...]` and `NOWAIT` / `SKIP LOCKED`).
fn consume_for_lock(tokens: &[Token], idx: usize) -> Option<usize> {
    if !tokens[idx].word_eq("FOR") {
        return None;
    }
    let w1 = next_non_space(tokens, idx + 1)?;
    let mut cur = if tokens[w1].word_eq("UPDATE") || tokens[w1].word_eq("SHARE") {
        w1 + 1
    } else if tokens[w1].word_eq("NO") {
        let w2 = next_non_space(tokens, w1 + 1)?;
        let w3 = next_non_space(tokens, w2 + 1)?;
        if tokens[w2].word_eq("KEY") && tokens[w3].word_eq("UPDATE") {
            w3 + 1
        } else {
            return None;
        }
    } else if tokens[w1].word_eq("KEY") {
        let w2 = next_non_space(tokens, w1 + 1)?;
        if tokens[w2].word_eq("SHARE") {
            w2 + 1
        } else {
            return None;
        }
    } else {
        return None;
    };

    // Optional `OF table_name [, table_name ...]`
    if let Some(of_idx) = next_non_space(tokens, cur)
        && tokens[of_idx].word_eq("OF")
        && let Some(mut tbl_idx) = next_non_space(tokens, of_idx + 1)
        && matches!(tokens[tbl_idx], Token::Word(_) | Token::Verbatim(_))
    {
        cur = tbl_idx + 1;
        while let Some(comma_idx) = next_non_space(tokens, cur) {
            if tokens[comma_idx].sym_eq(",")
                && let Some(next_tbl) = next_non_space(tokens, comma_idx + 1)
                && matches!(tokens[next_tbl], Token::Word(_) | Token::Verbatim(_))
            {
                tbl_idx = next_tbl;
                cur = tbl_idx + 1;
            } else {
                break;
            }
        }
    }

    // Optional `NOWAIT` or `SKIP LOCKED`
    if let Some(opt_idx) = next_non_space(tokens, cur) {
        if tokens[opt_idx].word_eq("NOWAIT") {
            cur = opt_idx + 1;
        } else if tokens[opt_idx].word_eq("SKIP")
            && let Some(locked_idx) = next_non_space(tokens, opt_idx + 1)
            && tokens[locked_idx].word_eq("LOCKED")
        {
            cur = locked_idx + 1;
        }
    }

    Some(cur)
}

/// Attempts to consume `AT TIME ZONE 'UTC'` starting at `idx`.
fn consume_at_time_zone_utc(tokens: &[Token], idx: usize) -> Option<usize> {
    if !tokens[idx].word_eq("AT") {
        return None;
    }
    let w1 = next_non_space(tokens, idx + 1)?;
    if !tokens[w1].word_eq("TIME") {
        return None;
    }
    let w2 = next_non_space(tokens, w1 + 1)?;
    if !tokens[w2].word_eq("ZONE") {
        return None;
    }
    let tz = next_non_space(tokens, w2 + 1)?;
    let Token::Verbatim(lit) = &tokens[tz] else {
        return None;
    };
    if lit.eq_ignore_ascii_case("'UTC'") {
        Some(tz + 1)
    } else {
        None
    }
}

/// Translates a PostgreSQL SQL query into SQLite-compatible SQL.
pub fn translate_pg_to_sqlite(sql: &str) -> String {
    let tokens = tokenize(sql);
    let mut out = String::with_capacity(sql.len() + 32);
    let mut i = 0usize;

    while i < tokens.len() {
        // 1. Strip PostgreSQL `::type[...][[]]` casts.
        if let Some(next) = consume_pg_cast(&tokens, i) {
            i = next;
            continue;
        }

        // 2. Rewrite `= ANY($n)` -> `IN (SELECT value FROM json_each(?n))`
        //    and `!= ALL($n)` / `<> ALL($n)` -> `NOT IN (SELECT value FROM json_each(?n))`.
        if let Some((replacement, next)) = consume_any_or_all(&tokens, i) {
            out.push_str(&replacement);
            i = next;
            continue;
        }

        // 3. Strip `FOR UPDATE` / `FOR NO KEY UPDATE` / `FOR SHARE` / `FOR KEY SHARE`.
        if let Some(next) = consume_for_lock(&tokens, i) {
            // Trim a single trailing space before the stripped clause if present to avoid
            // double spaces or trailing whitespace.
            if out.ends_with(' ') {
                out.pop();
            }
            i = next;
            continue;
        }

        // 4. Strip `AT TIME ZONE 'UTC'`.
        if let Some(next) = consume_at_time_zone_utc(&tokens, i) {
            if out.ends_with(' ') {
                out.pop();
            }
            i = next;
            continue;
        }

        // 5. Function mappings & ANSI `DATE '...'` literal normalization.
        match &tokens[i] {
            Token::Placeholder(num) => {
                out.push('?');
                out.push_str(num);
                i += 1;
            }
            Token::Word(w) if w.eq_ignore_ascii_case("NOW") => {
                if let Some(open) = next_non_space(&tokens, i + 1)
                    && tokens[open].sym_eq("(")
                    && let Some(close) = next_non_space(&tokens, open + 1)
                    && tokens[close].sym_eq(")")
                {
                    out.push_str("now()");
                    i = close + 1;
                    continue;
                }
                out.push_str(w);
                i += 1;
            }
            Token::Word(w) if w.eq_ignore_ascii_case("LEAST") => {
                if let Some(open) = next_non_space(&tokens, i + 1)
                    && tokens[open].sym_eq("(")
                {
                    out.push_str("pg_least(");
                    i = open + 1;
                    continue;
                }
                out.push_str(w);
                i += 1;
            }
            Token::Word(w) if w.eq_ignore_ascii_case("GREATEST") => {
                if let Some(open) = next_non_space(&tokens, i + 1)
                    && tokens[open].sym_eq("(")
                {
                    out.push_str("pg_greatest(");
                    i = open + 1;
                    continue;
                }
                out.push_str(w);
                i += 1;
            }
            Token::Word(w) if w.eq_ignore_ascii_case("DATE") => {
                if let Some(lit_idx) = next_non_space(&tokens, i + 1)
                    && let Token::Verbatim(lit) = &tokens[lit_idx]
                    && lit.starts_with('\'')
                    && lit.ends_with('\'')
                {
                    out.push_str(lit);
                    i = lit_idx + 1;
                    continue;
                }
                out.push_str(w);
                i += 1;
            }
            Token::Word(w) if w.eq_ignore_ascii_case("transaction") => {
                let is_table_pos = prev_non_space(&tokens, i).is_some_and(|p| {
                    matches!(
                        &tokens[p],
                        Token::Word(prev)
                            if prev.eq_ignore_ascii_case("FROM")
                                || prev.eq_ignore_ascii_case("JOIN")
                                || prev.eq_ignore_ascii_case("INTO")
                                || prev.eq_ignore_ascii_case("UPDATE")
                                || prev.eq_ignore_ascii_case("TABLE")
                    )
                });
                if is_table_pos {
                    out.push_str("\"transaction\"");
                } else {
                    out.push_str(w);
                }
                i += 1;
            }
            other => {
                out.push_str(other.as_str());
                i += 1;
            }
        }
    }

    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rewrites_numbered_placeholders() {
        assert_eq!(
            translate_pg_to_sqlite("SELECT * FROM account WHERE id = $1 AND member_id = $2"),
            "SELECT * FROM account WHERE id = ?1 AND member_id = ?2"
        );
    }

    #[test]
    fn rewrites_multi_digit_and_reused_placeholders() {
        assert_eq!(
            translate_pg_to_sqlite("SELECT $1, $10, $11, $2, $1"),
            "SELECT ?1, ?10, ?11, ?2, ?1"
        );
    }

    #[test]
    fn preserves_dollar_signs_inside_single_quoted_strings() {
        assert_eq!(
            translate_pg_to_sqlite(
                "SELECT '$1', 'price is $2::text', 'it''s $3' FROM t WHERE id = $1"
            ),
            "SELECT '$1', 'price is $2::text', 'it''s $3' FROM t WHERE id = ?1"
        );
    }

    #[test]
    fn preserves_casts_and_keywords_inside_line_comments() {
        assert_eq!(
            translate_pg_to_sqlite("SELECT id -- $1::text FOR UPDATE NOW()\nFROM t WHERE id = $1"),
            "SELECT id -- $1::text FOR UPDATE NOW()\nFROM t WHERE id = ?1"
        );
    }

    #[test]
    fn preserves_casts_and_keywords_inside_block_comments() {
        assert_eq!(
            translate_pg_to_sqlite(
                "SELECT /* $1::bigint ANY($2) FOR UPDATE */ id FROM t WHERE id = $1"
            ),
            "SELECT /* $1::bigint ANY($2) FOR UPDATE */ id FROM t WHERE id = ?1"
        );
    }

    #[test]
    fn preserves_double_quoted_identifiers() {
        assert_eq!(
            translate_pg_to_sqlite("SELECT \"now\", \"$1\" FROM \"transaction\" WHERE id = $1"),
            "SELECT \"now\", \"$1\" FROM \"transaction\" WHERE id = ?1"
        );
    }

    #[test]
    fn strips_text_and_varchar_casts() {
        assert_eq!(
            translate_pg_to_sqlite("SELECT type::text, name::varchar, code::varchar(32) FROM a"),
            "SELECT type, name, code FROM a"
        );
    }

    #[test]
    fn strips_integer_and_numeric_casts() {
        assert_eq!(
            translate_pg_to_sqlite(
                "SELECT $1::bigint, $2::int, $3::integer, $4::int4, $5::int8, $6::numeric(19, 8)"
            ),
            "SELECT ?1, ?2, ?3, ?4, ?5, ?6"
        );
    }

    #[test]
    fn strips_temporal_uuid_bool_json_casts() {
        assert_eq!(
            translate_pg_to_sqlite(
                "SELECT $1::timestamptz, $2::timestamp, $3::date, $4::uuid, $5::boolean, $6::bool, $7::jsonb"
            ),
            "SELECT ?1, ?2, ?3, ?4, ?5, ?6, ?7"
        );
    }

    #[test]
    fn strips_multi_word_timestamp_with_time_zone_cast() {
        assert_eq!(
            translate_pg_to_sqlite("SELECT $1::timestamp with time zone, $2::character varying"),
            "SELECT ?1, ?2"
        );
    }

    #[test]
    fn strips_picsou_enum_casts() {
        assert_eq!(
            translate_pg_to_sqlite(
                "INSERT INTO t VALUES ($1::account_type, $2::category_kind, $3::recurring_cadence, $4::recurring_status, $5::rate_basis, $6::requisition_status, $7::savings_product, $8::sharing_level)"
            ),
            "INSERT INTO t VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8)"
        );
    }

    #[test]
    fn strips_array_casts() {
        assert_eq!(
            translate_pg_to_sqlite("WHERE ($1::text[] IS NULL) AND ($2::bigint[] IS NULL)"),
            "WHERE (?1 IS NULL) AND (?2 IS NULL)"
        );
    }

    #[test]
    fn strips_chained_casts() {
        assert_eq!(
            translate_pg_to_sqlite("SELECT $1::bigint::text"),
            "SELECT ?1"
        );
    }

    #[test]
    fn rewrites_eq_any_with_dollar_placeholder() {
        assert_eq!(
            translate_pg_to_sqlite("SELECT id FROM category WHERE id = ANY($1)"),
            "SELECT id FROM category WHERE id IN (SELECT value FROM json_each(?1))"
        );
    }

    #[test]
    fn rewrites_eq_any_with_question_placeholder_and_cast() {
        assert_eq!(
            translate_pg_to_sqlite("SELECT id FROM category WHERE id = ANY(?1::bigint[])"),
            "SELECT id FROM category WHERE id IN (SELECT value FROM json_each(?1))"
        );
    }

    #[test]
    fn rewrites_not_eq_any_expression() {
        assert_eq!(
            translate_pg_to_sqlite(
                "DELETE FROM account_holding WHERE account_id = $1 AND NOT (ticker = ANY($2))"
            ),
            "DELETE FROM account_holding WHERE account_id = ?1 AND NOT (ticker IN (SELECT value FROM json_each(?2)))"
        );
    }

    #[test]
    fn rewrites_multiple_any_clauses_in_one_query() {
        assert_eq!(
            translate_pg_to_sqlite(
                "DELETE FROM account_holding WHERE account_id = ANY($1) AND ticker = ANY($2)"
            ),
            "DELETE FROM account_holding WHERE account_id IN (SELECT value FROM json_each(?1)) AND ticker IN (SELECT value FROM json_each(?2))"
        );
    }

    #[test]
    fn rewrites_nullable_array_filter_pattern_from_ledger() {
        assert_eq!(
            translate_pg_to_sqlite(
                "WHERE date >= $1 AND ($2::bigint[] IS NULL OR account_id = ANY($2)) ORDER BY account_id, date"
            ),
            "WHERE date >= ?1 AND (?2 IS NULL OR account_id IN (SELECT value FROM json_each(?2))) ORDER BY account_id, date"
        );
    }

    #[test]
    fn rewrites_ne_all_bang_equals() {
        assert_eq!(
            translate_pg_to_sqlite("SELECT id FROM account WHERE provider != ALL($1)"),
            "SELECT id FROM account WHERE provider NOT IN (SELECT value FROM json_each(?1))"
        );
    }

    #[test]
    fn rewrites_ne_all_angle_brackets() {
        assert_eq!(
            translate_pg_to_sqlite("SELECT id FROM account WHERE provider <> ALL($1)"),
            "SELECT id FROM account WHERE provider NOT IN (SELECT value FROM json_each(?1))"
        );
    }

    #[test]
    fn rewrites_upper_function_with_any() {
        assert_eq!(
            translate_pg_to_sqlite(
                "SELECT DISTINCT upper(ticker) FROM crypto_asset WHERE upper(ticker) = ANY($1)"
            ),
            "SELECT DISTINCT upper(ticker) FROM crypto_asset WHERE upper(ticker) IN (SELECT value FROM json_each(?1))"
        );
    }

    #[test]
    fn strips_for_update() {
        assert_eq!(
            translate_pg_to_sqlite("SELECT id FROM app_user WHERE id = $1 FOR UPDATE"),
            "SELECT id FROM app_user WHERE id = ?1"
        );
    }

    #[test]
    fn strips_for_no_key_update_and_for_share() {
        assert_eq!(
            translate_pg_to_sqlite("SELECT id FROM account WHERE id = $1 FOR NO KEY UPDATE NOWAIT"),
            "SELECT id FROM account WHERE id = ?1"
        );
        assert_eq!(
            translate_pg_to_sqlite("SELECT id FROM account WHERE id = $1 FOR SHARE SKIP LOCKED"),
            "SELECT id FROM account WHERE id = ?1"
        );
        assert_eq!(
            translate_pg_to_sqlite("SELECT id FROM account a WHERE a.id = $1 FOR UPDATE OF a"),
            "SELECT id FROM account a WHERE a.id = ?1"
        );
    }

    #[test]
    fn maps_now_function_case_insensitively() {
        assert_eq!(
            translate_pg_to_sqlite(
                "UPDATE app_user SET updated_at = NOW(), last_seen = now( ) WHERE id = $1"
            ),
            "UPDATE app_user SET updated_at = now(), last_seen = now() WHERE id = ?1"
        );
    }

    #[test]
    fn does_not_touch_now_as_column_name() {
        assert_eq!(
            translate_pg_to_sqlite("SELECT now_value, now FROM events"),
            "SELECT now_value, now FROM events"
        );
    }

    #[test]
    fn maps_least_and_greatest() {
        assert_eq!(
            translate_pg_to_sqlite("SELECT LEAST($1, $2), GREATEST(a, b, c) FROM t"),
            "SELECT pg_least(?1, ?2), pg_greatest(a, b, c) FROM t"
        );
    }

    #[test]
    fn strips_at_time_zone_utc_from_exchanges_and_wallets_queries() {
        assert_eq!(
            translate_pg_to_sqlite(
                "UPDATE account SET deleted_at = ($2::timestamptz AT TIME ZONE 'UTC'), updated_at = $2 WHERE id = $1"
            ),
            "UPDATE account SET deleted_at = (?2), updated_at = ?2 WHERE id = ?1"
        );
    }

    #[test]
    fn normalizes_ansi_date_literal() {
        assert_eq!(
            translate_pg_to_sqlite(
                "INSERT INTO price_snapshot (ticker, date, price_eur) VALUES ('CW8.PA', DATE '2026-01-15', $1)"
            ),
            "INSERT INTO price_snapshot (ticker, date, price_eur) VALUES ('CW8.PA', '2026-01-15', ?1)"
        );
    }

    #[test]
    fn does_not_touch_date_column_or_function() {
        assert_eq!(
            translate_pg_to_sqlite(
                "SELECT date, DATE(created_at) FROM \"transaction\" WHERE date >= $1"
            ),
            "SELECT date, DATE(created_at) FROM \"transaction\" WHERE date >= ?1"
        );
    }

    #[test]
    fn preserves_on_conflict_do_update_returning() {
        let sql = "INSERT INTO app_setting (setting_key, value, updated_at) \
                   VALUES ($1, $2, NOW()) \
                   ON CONFLICT (setting_key) DO UPDATE SET value = EXCLUDED.value, updated_at = NOW() \
                   RETURNING setting_key, value";
        let expected = "INSERT INTO app_setting (setting_key, value, updated_at) \
                        VALUES (?1, ?2, now()) \
                        ON CONFLICT (setting_key) DO UPDATE SET value = EXCLUDED.value, updated_at = now() \
                        RETURNING setting_key, value";
        assert_eq!(translate_pg_to_sqlite(sql), expected);
    }

    #[test]
    fn preserves_cast_case_insensitivity() {
        assert_eq!(
            translate_pg_to_sqlite("SELECT $1::TEXT, $2::BigInt, $3::CATEGORY_KIND"),
            "SELECT ?1, ?2, ?3"
        );
    }

    #[test]
    fn handles_empty_and_simple_queries() {
        assert_eq!(translate_pg_to_sqlite(""), "");
        assert_eq!(translate_pg_to_sqlite("SELECT 1"), "SELECT 1");
    }

    #[test]
    fn cache_returns_identical_translation() {
        let sql = "SELECT id FROM account WHERE id = ANY($1::bigint[]) FOR UPDATE";
        let first = translate_cached(sql);
        let second = translate_cached(sql);
        assert_eq!(first.as_ref(), second.as_ref());
        assert_eq!(
            first.as_ref(),
            "SELECT id FROM account WHERE id IN (SELECT value FROM json_each(?1))"
        );
    }

    #[test]
    fn read_only_classifier_handles_comments_and_writes() {
        assert!(is_read_only_sql("SELECT 1"));
        assert!(is_read_only_sql(
            "  -- comment\n /* block */ select * from account"
        ));
        assert!(is_read_only_sql("VALUES (1, 2)"));
        assert!(!is_read_only_sql("INSERT INTO t VALUES (1) RETURNING id"));
        assert!(!is_read_only_sql("UPDATE t SET a = 1 RETURNING id"));
        assert!(!is_read_only_sql("DELETE FROM t WHERE id = 1"));
        assert!(!is_read_only_sql(
            "WITH x AS (DELETE FROM t RETURNING id) SELECT * FROM x"
        ));
        assert!(!is_read_only_sql(
            "WITH x AS (SELECT 1) INSERT INTO t SELECT * FROM x"
        ));
        assert!(!is_read_only_sql("WITH x AS (SELECT 1) UPDATE t SET a = 1"));
        assert!(!is_read_only_sql(
            "WITH x AS (SELECT 1) REPLACE INTO t VALUES (1)"
        ));
        assert!(is_read_only_sql(
            "WITH cte AS (SELECT id FROM account) SELECT * FROM cte"
        ));
        assert!(is_read_only_sql(
            "WITH RECURSIVE r(n) AS (VALUES (1) UNION ALL SELECT n + 1 FROM r WHERE n < 3) SELECT n FROM r"
        ));
        assert!(is_read_only_sql(
            "WITH cte AS (SELECT 'INSERT' AS op, 'DELETE' AS verb) SELECT * FROM cte"
        ));
        assert!(is_read_only_sql(
            "WITH /* UPDATE */ cte AS (SELECT id FROM account) -- REPLACE\n SELECT * FROM cte"
        ));
        assert!(!is_read_only_sql("PRAGMA user_version"));
    }

    #[test]
    fn quotes_bare_transaction_table_without_touching_begin_transaction() {
        assert_eq!(
            translate_pg_to_sqlite("SELECT * FROM transaction t WHERE t.id = $1"),
            "SELECT * FROM \"transaction\" t WHERE t.id = ?1"
        );
        assert_eq!(
            translate_pg_to_sqlite("INSERT INTO transaction (id) VALUES ($1)"),
            "INSERT INTO \"transaction\" (id) VALUES (?1)"
        );
        assert_eq!(
            translate_pg_to_sqlite("UPDATE transaction SET amount = $1"),
            "UPDATE \"transaction\" SET amount = ?1"
        );
        assert_eq!(
            translate_pg_to_sqlite("BEGIN TRANSACTION"),
            "BEGIN TRANSACTION"
        );
    }
}
