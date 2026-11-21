//! TelemetryDsn and TelemetryEnvelopeSanitizer: the configured collector, and a client envelope
//! rebuilt from its event items only, each re-scrubbed, under the configured DSN.

use std::sync::LazyLock;

use regex::Regex;
use serde_json::{Map, Value};

use super::scrubber;

const MAX_EVENTS: usize = 5;
static SENT_AT: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"^[0-9T:.+\-Z]{10,40}$").expect("static regex"));
static PROJECT_ID: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"^[A-Za-z0-9_\-]{1,64}$").expect("static regex"));
static PUBLIC_KEY: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"^[A-Za-z0-9_\-]{1,128}$").expect("static regex"));

/// scheme://<publicKey>@host[:port]/[prefix/]<projectId>
#[derive(Debug, Clone)]
pub struct Dsn {
    pub raw: String,
    scheme: String,
    authority: String,
    path_prefix: String,
    project_id: String,
    public_key: String,
}

impl Dsn {
    pub fn parse(dsn: &str) -> Option<Self> {
        let raw = dsn.trim();
        if raw.is_empty() {
            return None;
        }
        let url = url::Url::parse(raw).ok()?;
        if !matches!(url.scheme(), "http" | "https") {
            return None;
        }
        let host = url.host_str()?;
        let key = url.username();
        if key.is_empty() {
            return None;
        }
        let path = url.path();
        let slash = path.rfind('/')?;
        if slash == path.len() - 1 {
            return None;
        }
        let project_id = &path[slash + 1..];
        if !PROJECT_ID.is_match(project_id) || !PUBLIC_KEY.is_match(key) {
            return None;
        }
        let authority = match url.port() {
            Some(port) => format!("{host}:{port}"),
            None => host.to_owned(),
        };
        Some(Self {
            raw: raw.to_owned(),
            scheme: url.scheme().to_owned(),
            authority,
            path_prefix: path[..slash].to_owned(),
            project_id: project_id.to_owned(),
            public_key: key.to_owned(),
        })
    }

    pub fn envelope_url(&self) -> String {
        format!(
            "{}://{}{}/api/{}/envelope/",
            self.scheme, self.authority, self.path_prefix, self.project_id
        )
    }

    pub fn auth_header(&self) -> String {
        format!(
            "Sentry sentry_version=7, sentry_key={}, sentry_client=picsou-tunnel",
            self.public_key
        )
    }
}

fn read_line<'a>(raw: &'a [u8], cursor: &mut usize) -> &'a [u8] {
    let start = *cursor;
    while *cursor < raw.len() && raw[*cursor] != b'\n' {
        *cursor += 1;
    }
    let line = &raw[start..*cursor];
    if *cursor < raw.len() {
        *cursor += 1;
    }
    line
}

fn read_json(bytes: &[u8]) -> Option<Map<String, Value>> {
    if bytes.is_empty() {
        return None;
    }
    match serde_json::from_slice::<Value>(bytes) {
        Ok(Value::Object(map)) => Some(map),
        _ => {
            tracing::error!("telemetry.envelope.parse.failed");
            None
        }
    }
}

/// The envelope to forward, or None when nothing survives or the input is garbage.
pub fn sanitize(raw: &[u8], configured_dsn: &str) -> Option<Vec<u8>> {
    let mut cursor = 0;
    let header = read_json(read_line(raw, &mut cursor))?;
    let mut events: Vec<Vec<u8>> = Vec::new();
    while cursor < raw.len() && events.len() < MAX_EVENTS {
        let item_header_bytes = read_line(raw, &mut cursor);
        if item_header_bytes.is_empty() {
            continue;
        }
        let Some(item_header) = read_json(item_header_bytes) else {
            break;
        };
        let payload = match item_header.get("length") {
            Some(length) => {
                let len = exact_length(length, raw.len() - cursor)?;
                let payload = &raw[cursor..cursor + len];
                cursor += len;
                if cursor < raw.len() && raw[cursor] == b'\n' {
                    cursor += 1;
                }
                payload
            }
            None => read_line(raw, &mut cursor),
        };
        if item_header.get("type").and_then(Value::as_str) != Some("event") {
            continue;
        }
        let Some(event) = read_json(payload) else {
            continue;
        };
        events.push(
            Value::Object(scrubber::scrub_event(&event))
                .to_string()
                .into_bytes(),
        );
    }
    if events.is_empty() {
        return None;
    }
    let mut out_header = Map::new();
    if let Some(sent_at) = header
        .get("sent_at")
        .and_then(Value::as_str)
        .filter(|s| SENT_AT.is_match(s))
    {
        out_header.insert("sent_at".into(), sent_at.into());
    }
    out_header.insert("dsn".into(), configured_dsn.into());
    let mut out = Value::Object(out_header).to_string().into_bytes();
    out.push(b'\n');
    for event in events {
        let mut item = Map::new();
        item.insert("type".into(), "event".into());
        item.insert("length".into(), event.len().into());
        out.extend_from_slice(Value::Object(item).to_string().as_bytes());
        out.push(b'\n');
        out.extend_from_slice(&event);
        out.push(b'\n');
    }
    Some(out)
}

/// BigDecimal.longValueExact of the declared length, within what is left.
fn exact_length(value: &Value, remaining: usize) -> Option<usize> {
    let Value::Number(n) = value else { return None };
    let text = n.to_string();
    let whole = match text.split_once('.') {
        Some((w, fraction)) if fraction.chars().all(|c| c == '0') => w.to_owned(),
        Some(_) => return None,
        None => text,
    };
    whole.parse::<usize>().ok().filter(|l| *l <= remaining)
}

#[cfg(test)]
mod tests {
    use super::*;

    const DSN: &str = "https://abc123@collector.example/42";

    #[test]
    fn keeps_events_only_and_the_configured_dsn() {
        let raw = b"{\"dsn\":\"https://evil@x/1\",\"sent_at\":\"2026-01-01T00:00:00Z\"}\n{\"type\":\"session\"}\n{}\n{\"type\":\"event\"}\n{\"level\":\"error\",\"user\":{\"id\":1}}\n";
        let out = String::from_utf8(sanitize(raw, DSN).unwrap()).unwrap();
        let lines: Vec<&str> = out.lines().collect();
        assert_eq!(
            lines[0],
            "{\"sent_at\":\"2026-01-01T00:00:00Z\",\"dsn\":\"https://abc123@collector.example/42\"}"
        );
        assert_eq!(lines[1], "{\"type\":\"event\",\"length\":17}");
        assert_eq!(lines[2], "{\"level\":\"error\"}");
    }

    #[test]
    fn a_declared_length_past_the_end_drops_the_envelope() {
        let raw = b"{}\n{\"type\":\"event\",\"length\":999}\n{}\n";
        assert!(sanitize(raw, DSN).is_none());
    }

    #[test]
    fn parses_the_dsn() {
        let dsn = Dsn::parse("https://key:secret@host.example:8443/prefix/7").unwrap();
        assert_eq!(
            dsn.envelope_url(),
            "https://host.example:8443/prefix/api/7/envelope/"
        );
        assert!(dsn.auth_header().contains("sentry_key=key"));
        assert!(Dsn::parse("ftp://k@h/1").is_none());
        assert!(Dsn::parse("https://h/1").is_none());
    }
}
