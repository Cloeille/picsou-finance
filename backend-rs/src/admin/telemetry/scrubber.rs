//! TelemetryScrubber: allowlist scrubbing of Sentry-protocol events. Nothing is copied unless
//! named here, and every kept free-text string goes through scrub_text. Java's \w, \d and \s are
//! ASCII classes, so the patterns spell them out.

use std::sync::LazyLock;

use fancy_regex::{Captures, Regex};
use serde_json::{Map, Value};

const MAX_INPUT: usize = 4_000;
const MAX_NAME: usize = 200;
const MAX_TAG_VALUE: usize = 64;
const MAX_EXCEPTIONS: usize = 10;
const MAX_FRAMES: usize = 100;
const MAX_FINGERPRINT: usize = 10;
const LEVELS: &[&str] = &["fatal", "error", "warning", "info", "debug"];
const USAGE_MESSAGES: &[&str] = &["page_view", "feature_used"];
/// Must stay in sync with FeatureEvent in frontend/src/lib/telemetry.ts.
const FEATURES: &[&str] = &[
    "sync_triggered",
    "export_downloaded",
    "goal_created",
    "budget_viewed",
];
const ROUTE_NOT_FOUND: &str = "not-found";
const TOKEN_SEPARATORS: &str = "/\\(),;:'\"<>[]{}|=&?#";

fn re(pattern: &str) -> Regex {
    Regex::new(pattern).expect("static regex")
}

macro_rules! pattern {
    ($name:ident, $p:expr) => {
        static $name: LazyLock<Regex> = LazyLock::new(|| re($p));
    };
}

pattern!(EVENT_ID, r"^[0-9a-fA-F-]{32,36}$");
pattern!(TIMESTAMP, r"^[0-9T:.+\-Z ]{10,40}$");
pattern!(PLATFORM, r"^[a-z]{1,20}$");
pattern!(LOGGER, r"^[A-Za-z0-9_.$\-]{1,100}$");
pattern!(RELEASE, r"^[A-Za-z0-9_.+@\-]{1,100}$");
pattern!(ENVIRONMENT, r"^[A-Za-z0-9_.\-]{1,64}$");
pattern!(MECHANISM_TYPE, r"^[A-Za-z0-9_.\-]{1,64}$");
pattern!(SDK_NAME, r"^[A-Za-z0-9_./\-]{1,64}$");
pattern!(VERSION, r"^[A-Za-z0-9_.+\-]{1,32}$");
pattern!(CONTEXT_NAME, r"^[A-Za-z0-9_ .+\-]{1,64}$");
pattern!(
    TAG_KEY,
    r"^(?:route|event|feature|ab\.[A-Za-z0-9_.\-]{1,60})$"
);
pattern!(ROUTE_TAG, r"^/[A-Za-z0-9_:./\-]{0,63}$");
pattern!(NAME_TAG, r"^[A-Za-z0-9_.\-]{1,64}$");
pattern!(URL, r#"https?://[^ \t\n\x0B\f\r"'<>()\[\]{}]+"#);
pattern!(ORIGIN, r"^[a-zA-Z][a-zA-Z0-9+.\-]*://[^/?#]*");
pattern!(JWT, r"eyJ[A-Za-z0-9_\-]+\.[A-Za-z0-9_\-]+\.[A-Za-z0-9_\-]*");
pattern!(BEARER, r"(?i)\bBearer[ \t\n\x0B\f\r]+[A-Za-z0-9_\-.~+/]+=*");
pattern!(
    SECRET_PAIR,
    r#"(?i)\b(key|token|password|passwd|secret|api[_-]?key|access[_-]?token|authorization)(["']?[ \t\n\x0B\f\r]*[=:][ \t\n\x0B\f\r]*["']?)[^ \t\n\x0B\f\r&;,"'}]+"#
);
pattern!(
    EMAIL,
    r"(?<![A-Za-z0-9_.+\-])(?>[A-Za-z0-9_.+\-]+)@(?>[A-Za-z0-9_\-]+)(?:\.(?>[A-Za-z0-9_\-]+))+"
);
pattern!(IBAN, r"\b[A-Z]{2}[0-9]{2}(?: ?[A-Z0-9]){10,30}\b");
pattern!(CARD, r"(?<![0-9])(?:[0-9][ -]?){12,18}[0-9](?![0-9])");
pattern!(IPV4, r"(?<![0-9.])[0-9]{1,3}(?:\.[0-9]{1,3}){3}(?![0-9.])");
pattern!(
    AMOUNT_SIGN_BEFORE,
    r"[€$£][ \t\n\x0B\f\r]?[0-9]+(?:[ \u{a0}\u{202f}.,][0-9]{3})*(?:[.,][0-9]+)?"
);
pattern!(
    AMOUNT_CODE_AFTER,
    r"(?i)(?<![0-9])[0-9]+(?:[ \u{a0}\u{202f}.,][0-9]{3})*(?:[.,][0-9]+)?[ \t\n\x0B\f\r]?(?:€|\$|£|EUR|USD|GBP|CHF|euros?|dollars?)(?![A-Za-z])"
);
pattern!(
    AMOUNT_DECIMALS,
    r"(?<![0-9])[0-9]+(?:[ \u{a0}\u{202f}.,][0-9]{3})*[.,][0-9]{2}(?![0-9])"
);
pattern!(LONG_NUMBER, r"[0-9]{4,}");
pattern!(
    UUID_SEGMENT,
    r"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
);
pattern!(HEX_SEGMENT, r"^[0-9a-fA-F]{12,}$");
pattern!(OPAQUE_SEGMENT, r"^[A-Za-z0-9_=\-]{20,}$");

fn matches(regex: &Regex, value: &str) -> bool {
    regex.is_match(value).unwrap_or(false)
}

fn replace_all(regex: &Regex, value: &str, replacement: &str) -> String {
    regex.replace_all(value, replacement).into_owned()
}

fn take_chars(value: &str, max: usize) -> String {
    value.chars().take(max).collect()
}

/// Scrubs a free-text string. URLs first: they carry the most ids and tokens.
pub fn scrub_text(input: &str) -> String {
    let mut s = take_chars(input, MAX_INPUT);
    s = URL
        .replace_all(&s, |c: &Captures<'_, str>| scrub_url(&c[0]))
        .into_owned();
    s = replace_all(&JWT, &s, "[token]");
    s = replace_all(&BEARER, &s, "[token]");
    s = replace_all(&SECRET_PAIR, &s, "${1}${2}[token]");
    s = replace_all(&EMAIL, &s, "[email]");
    s = replace_all(&IBAN, &s, "[iban]");
    s = replace_all(&CARD, &s, "[card]");
    s = replace_all(&IPV4, &s, "[ip]");
    s = replace_all(&AMOUNT_SIGN_BEFORE, &s, "[amount]");
    s = replace_all(&AMOUNT_CODE_AFTER, &s, "[amount]");
    s = replace_all(&AMOUNT_DECIMALS, &s, "[amount]");
    replace_all(&LONG_NUMBER, &s, "[n]")
}

/// Drops scheme, host, query and fragment and replaces id-looking segments with :id.
pub fn route_template(path: &str) -> String {
    let p = strip_query_and_fragment(path);
    let start = ORIGIN.find(p).ok().flatten().map_or(0, |m| m.end());
    template_path(&p[start..])
}

pub fn scrub_filename(filename: &str) -> String {
    cut(
        &scrub_text(&route_template(&pre_cut(filename, MAX_NAME))),
        MAX_NAME,
    )
}

fn scrub_url(url: &str) -> String {
    let no_query = strip_query_and_fragment(url);
    let Some(origin) = ORIGIN.find(no_query).ok().flatten() else {
        return "[url]".to_owned();
    };
    let mut o = origin.as_str().to_owned();
    if let Some(at) = o.rfind('@') {
        let scheme_end = o.find("://").map_or(0, |i| i + 3);
        o = format!("{}{}", &o[..scheme_end], &o[at + 1..]);
    }
    format!("{o}{}", template_path(&no_query[origin.end()..]))
}

fn strip_query_and_fragment(s: &str) -> &str {
    let mut end = s.len();
    if let Some(q) = s.find('?') {
        end = q;
    }
    if let Some(h) = s.find('#')
        && h < end
    {
        end = h;
    }
    &s[..end]
}

fn template_path(path: &str) -> String {
    path.split('/')
        .map(|seg| if is_id_segment(seg) { ":id" } else { seg })
        .collect::<Vec<_>>()
        .join("/")
}

fn is_id_segment(seg: &str) -> bool {
    if seg.is_empty() {
        return false;
    }
    if seg.chars().all(|c| c.is_numeric()) {
        return true;
    }
    if matches(&UUID_SEGMENT, seg) || matches(&HEX_SEGMENT, seg) {
        return true;
    }
    matches(&OPAQUE_SEGMENT, seg) && seg.chars().any(|c| c.is_ascii_digit())
}

/// Bounds the regex work per field: at most twice the kept length, cut after the last token
/// separator so a partial email, IBAN or number never survives as a fragment.
pub fn pre_cut(s: &str, max: usize) -> String {
    let chars: Vec<char> = s.chars().collect();
    let limit = 2 * max;
    if chars.len() <= limit {
        return s.to_owned();
    }
    let mut end = limit;
    while end > 0 && !is_separator(chars[end - 1]) {
        end -= 1;
    }
    chars[..end].iter().collect()
}

fn is_separator(c: char) -> bool {
    c.is_whitespace() || TOKEN_SEPARATORS.contains(c)
}

fn cut(s: &str, max: usize) -> String {
    take_chars(s, max)
}

fn matching(value: Option<&Value>, regex: &Regex) -> Option<Value> {
    value
        .and_then(Value::as_str)
        .filter(|s| matches(regex, s))
        .map(|s| Value::String(s.to_owned()))
}

fn put(out: &mut Map<String, Value>, key: &str, value: Option<Value>) {
    if let Some(v) = value {
        out.insert(key.to_owned(), v);
    }
}

fn put_text(out: &mut Map<String, Value>, key: &str, value: Option<&Value>, max: usize) {
    if let Some(s) = value.and_then(Value::as_str) {
        out.insert(
            key.to_owned(),
            cut(&scrub_text(&pre_cut(s, max)), max).into(),
        );
    }
}

/// Number.intValue() of a JSON number: the integer part, never through a binary float.
fn int_value(value: &Value) -> Option<i64> {
    let Value::Number(n) = value else { return None };
    n.as_i64().or_else(|| {
        n.to_string()
            .split(['.', 'e', 'E'])
            .next()
            .and_then(|whole| whole.parse::<i64>().ok())
    })
}

/// Rebuilds a minimal event from the allowlist; never mutates the input.
pub fn scrub_event(raw: &Map<String, Value>) -> Map<String, Value> {
    let mut out = Map::new();
    put(
        &mut out,
        "event_id",
        matching(raw.get("event_id"), &EVENT_ID),
    );
    match raw.get("timestamp") {
        Some(ts @ Value::Number(_)) => {
            out.insert("timestamp".into(), ts.clone());
        }
        other => put(&mut out, "timestamp", matching(other, &TIMESTAMP)),
    }
    put(
        &mut out,
        "platform",
        matching(raw.get("platform"), &PLATFORM),
    );
    if let Some(level) = raw
        .get("level")
        .and_then(Value::as_str)
        .filter(|l| LEVELS.contains(l))
    {
        out.insert("level".into(), level.into());
    }
    put(&mut out, "logger", matching(raw.get("logger"), &LOGGER));
    put(&mut out, "release", matching(raw.get("release"), &RELEASE));
    put(
        &mut out,
        "environment",
        matching(raw.get("environment"), &ENVIRONMENT),
    );
    if let Some(message) = scrub_message(raw.get("message")) {
        out.insert("message".into(), message.into());
    }
    if let Some(exception) = scrub_exceptions(raw.get("exception")) {
        out.insert("exception".into(), Value::Object(exception));
    }
    let tags = scrub_tags(raw.get("tags"));
    if !tags.is_empty() {
        out.insert("tags".into(), Value::Object(tags));
    }
    let contexts = scrub_contexts(raw.get("contexts"));
    if !contexts.is_empty() {
        out.insert("contexts".into(), Value::Object(contexts));
    }
    let fingerprint = scrub_fingerprint(raw.get("fingerprint"));
    if !fingerprint.is_empty() {
        out.insert("fingerprint".into(), fingerprint.into());
    }
    let sdk = scrub_sdk(raw.get("sdk"));
    if !sdk.is_empty() {
        out.insert("sdk".into(), Value::Object(sdk));
    }
    // Transaction names survive only when they are a route, and then only templated.
    if let Some(t) = raw
        .get("transaction")
        .and_then(Value::as_str)
        .filter(|t| t.starts_with('/'))
    {
        out.insert(
            "transaction".into(),
            cut(
                &scrub_text(&route_template(&pre_cut(t, MAX_NAME))),
                MAX_NAME,
            )
            .into(),
        );
    }
    out
}

fn scrub_message(message: Option<&Value>) -> Option<String> {
    let usage = |v: Option<&Value>| {
        v.and_then(Value::as_str)
            .filter(|s| USAGE_MESSAGES.contains(s))
            .map(str::to_owned)
    };
    match message? {
        m @ Value::String(_) => usage(Some(m)),
        Value::Object(m) => usage(m.get("formatted")).or_else(|| usage(m.get("message"))),
        _ => None,
    }
}

fn scrub_exceptions(exception: Option<&Value>) -> Option<Map<String, Value>> {
    let values = match exception? {
        Value::Object(m) => m.get("values")?.as_array()?,
        Value::Array(l) => l,
        _ => return None,
    };
    let kept: Vec<Value> = values
        .iter()
        .filter_map(Value::as_object)
        .take(MAX_EXCEPTIONS)
        .map(|ex| Value::Object(scrub_exception(ex)))
        .collect();
    if kept.is_empty() {
        return None;
    }
    let mut out = Map::new();
    out.insert("values".into(), kept.into());
    Some(out)
}

fn scrub_exception(ex: &Map<String, Value>) -> Map<String, Value> {
    let mut out = Map::new();
    put_text(&mut out, "type", ex.get("type"), MAX_NAME);
    put_text(&mut out, "module", ex.get("module"), MAX_NAME);
    if let Some(mech) = ex.get("mechanism").and_then(Value::as_object) {
        let mut m = Map::new();
        put(&mut m, "type", matching(mech.get("type"), &MECHANISM_TYPE));
        if let Some(b) = mech.get("handled").and_then(Value::as_bool) {
            m.insert("handled".into(), b.into());
        }
        if !m.is_empty() {
            out.insert("mechanism".into(), Value::Object(m));
        }
    }
    if let Some(frames) = ex
        .get("stacktrace")
        .and_then(Value::as_object)
        .and_then(|st| st.get("frames"))
        .and_then(Value::as_array)
    {
        let from = frames.len().saturating_sub(MAX_FRAMES);
        let kept: Vec<Value> = frames[from..]
            .iter()
            .filter_map(Value::as_object)
            .map(|f| Value::Object(scrub_frame(f)))
            .collect();
        if !kept.is_empty() {
            let mut stacktrace = Map::new();
            stacktrace.insert("frames".into(), kept.into());
            out.insert("stacktrace".into(), Value::Object(stacktrace));
        }
    }
    out
}

fn scrub_frame(frame: &Map<String, Value>) -> Map<String, Value> {
    let mut out = Map::new();
    if let Some(f) = frame.get("filename").and_then(Value::as_str) {
        out.insert("filename".into(), scrub_filename(f).into());
    }
    put_text(&mut out, "function", frame.get("function"), MAX_NAME);
    put_text(&mut out, "module", frame.get("module"), MAX_NAME);
    for key in ["lineno", "colno"] {
        if let Some(n) = frame.get(key).and_then(int_value).filter(|n| *n >= 0) {
            out.insert(key.into(), n.into());
        }
    }
    if let Some(b) = frame.get("in_app").and_then(Value::as_bool) {
        out.insert("in_app".into(), b.into());
    }
    out
}

fn tag_text(value: &Value) -> Option<String> {
    match value {
        Value::String(s) => Some(s.clone()),
        Value::Number(n) => Some(n.to_string()),
        Value::Bool(b) => Some(b.to_string()),
        _ => None,
    }
}

fn scrub_tags(tags: Option<&Value>) -> Map<String, Value> {
    let mut out = Map::new();
    let Some(map) = tags.and_then(Value::as_object) else {
        return out;
    };
    for (key, raw) in map {
        if !matches(&TAG_KEY, key) {
            continue;
        }
        let Some(text) = tag_text(raw) else { continue };
        let mut value = pre_cut(&text, MAX_TAG_VALUE);
        if key == "route" {
            value = route_template(&value);
        }
        let value = cut(&scrub_text(&value), MAX_TAG_VALUE);
        let allowed = match key.as_str() {
            "feature" => FEATURES.contains(&value.as_str()),
            "route" => value == ROUTE_NOT_FOUND || matches(&ROUTE_TAG, &value),
            _ => matches(&NAME_TAG, &value),
        };
        if allowed {
            out.insert(key.clone(), value.into());
        }
    }
    out
}

fn scrub_contexts(contexts: Option<&Value>) -> Map<String, Value> {
    let mut out = Map::new();
    let Some(c) = contexts.and_then(Value::as_object) else {
        return out;
    };
    for (key, with_version) in [("os", false), ("browser", false), ("runtime", true)] {
        let Some(m) = c.get(key).and_then(Value::as_object) else {
            continue;
        };
        let mut kept = Map::new();
        put(&mut kept, "name", matching(m.get("name"), &CONTEXT_NAME));
        if with_version {
            put(&mut kept, "version", matching(m.get("version"), &VERSION));
        }
        if !kept.is_empty() {
            out.insert(key.into(), Value::Object(kept));
        }
    }
    out
}

fn scrub_fingerprint(fingerprint: Option<&Value>) -> Vec<String> {
    fingerprint
        .and_then(Value::as_array)
        .map(|l| {
            l.iter()
                .filter_map(Value::as_str)
                .take(MAX_FINGERPRINT)
                .map(|s| cut(&scrub_text(&pre_cut(s, 100)), 100))
                .collect()
        })
        .unwrap_or_default()
}

fn scrub_sdk(sdk: Option<&Value>) -> Map<String, Value> {
    let mut out = Map::new();
    if let Some(m) = sdk.and_then(Value::as_object) {
        put(&mut out, "name", matching(m.get("name"), &SDK_NAME));
        put(&mut out, "version", matching(m.get("version"), &VERSION));
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn scrubs_the_usual_suspects() {
        assert_eq!(
            scrub_text("mail jane.doe@example.com now"),
            "mail [email] now"
        );
        assert_eq!(
            scrub_text("iban FR7630006000011234567890189"),
            "iban [iban]"
        );
        assert_eq!(scrub_text("from 192.168.1.20"), "from [ip]");
        assert_eq!(scrub_text("paid 1 234,56 €"), "paid [amount]");
        assert_eq!(scrub_text("Bearer abc.def"), "[token]");
        assert_eq!(scrub_text("password=hunter2&x"), "password=[token]&x");
        assert_eq!(
            scrub_text("see https://user:pw@host.example/accounts/123?x=1"),
            "see https://host.example/accounts/:id"
        );
        assert_eq!(scrub_text("order 98765"), "order [n]");
    }

    #[test]
    fn templates_routes() {
        assert_eq!(route_template("/accounts/123?x=1"), "/accounts/:id");
        assert_eq!(
            route_template("https://h/a/0f8fad5b-d9cb-469f-a165-70867728950e"),
            "/a/:id"
        );
    }
}
