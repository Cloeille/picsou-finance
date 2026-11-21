//! The OAuth2 authorization server of Spring Authorization Server, as AuthorizationServerConfig
//! configures it: authorization code with PKCE for public clients (the iOS app and the MCP clients
//! that register themselves), the consent screen, self-contained HS256 access tokens, and the
//! JDBC tables, read and written in the format JdbcOAuth2AuthorizationService uses.

pub mod apps;
pub mod clients;
pub mod jackson;
pub mod server;

use chrono::{DateTime, NaiveDateTime, TimeZone, Utc};

use crate::AppState;

/// The ClientSettings key that marks a client registered by an MCP consumer.
pub const MCP_CLIENT_SETTING: &str = "settings.client.picsou-mcp";
pub const AUTHORIZATION_CODE_TTL: chrono::Duration = chrono::Duration::minutes(5);

/// The AS tables hold timestamp without time zone, written in the JVM zone (APP_TIMEZONE).
pub fn to_local(state: &AppState, at: DateTime<Utc>) -> NaiveDateTime {
    at.with_timezone(&state.clock.zone()).naive_local()
}

pub fn from_local(state: &AppState, at: NaiveDateTime) -> DateTime<Utc> {
    state
        .clock
        .zone()
        .from_local_datetime(&at)
        .earliest()
        .map_or_else(|| at.and_utc(), |t| t.with_timezone(&Utc))
}

/// UriUtils.encode: everything but the unreserved characters is percent-encoded.
pub fn encode_component(value: &str) -> String {
    let mut out = String::with_capacity(value.len());
    for byte in value.bytes() {
        match byte {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'.' | b'_' | b'~' => {
                out.push(char::from(byte));
            }
            other => out.push_str(&format!("%{other:02X}")),
        }
    }
    out
}

/// UriComponentsBuilder.encode() for a query parameter value: pchar, '/' and '?' stay, '=' and
/// '&' do not.
pub fn encode_query_value(value: &str) -> String {
    let mut out = String::with_capacity(value.len());
    for byte in value.bytes() {
        let keep = byte.is_ascii_alphanumeric()
            || b"-._~!$'()*+,;:@/?".contains(&byte);
        if keep {
            out.push(char::from(byte));
        } else {
            out.push_str(&format!("%{byte:02X}"));
        }
    }
    out
}
