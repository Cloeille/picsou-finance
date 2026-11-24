//! The client address and the public origin of a request, as Tomcat resolves them: the
//! X-Forwarded-For client only from a trusted proxy (RemoteIpValve), the forwarded scheme, host
//! and port from any peer (ForwardedOriginCustomizer).

use std::net::{IpAddr, SocketAddr};

use axum::extract::{ConnectInfo, FromRequestParts, Request};
use axum::http::HeaderMap;
use axum::http::request::Parts;

use crate::config::TrustedProxies;

/// The resolved client address of a request (ClientIp.resolve).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ClientIp(pub IpAddr);

impl ClientIp {
    /// The address as Java's getRemoteAddr() prints it, which is what rate-limit keys, audit rows
    /// and session ip prefixes store.
    pub fn as_java_string(&self) -> String {
        java_ip_string(&self.0)
    }

    pub fn is_loopback(&self) -> bool {
        is_loopback_ip(&self.0)
    }
}

/// The raw TCP peer address before any X-Forwarded-For resolution.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct PeerIp(pub IpAddr);

impl PeerIp {
    pub fn is_loopback(&self) -> bool {
        is_loopback_ip(&self.0)
    }
}

pub fn is_loopback_ip(ip: &IpAddr) -> bool {
    match ip {
        IpAddr::V4(v4) => v4.is_loopback(),
        IpAddr::V6(v6) => {
            v6.is_loopback() || v6.to_ipv4_mapped().is_some_and(|v4| v4.is_loopback())
        }
    }
}

impl<S: Send + Sync> FromRequestParts<S> for PeerIp {
    type Rejection = std::convert::Infallible;

    async fn from_request_parts(parts: &mut Parts, _: &S) -> Result<Self, Self::Rejection> {
        Ok(parts
            .extensions
            .get::<PeerIp>()
            .copied()
            .unwrap_or(PeerIp(IpAddr::from([127, 0, 0, 1]))))
    }
}

impl<S: Send + Sync> FromRequestParts<S> for ClientIp {
    type Rejection = std::convert::Infallible;

    async fn from_request_parts(parts: &mut Parts, _: &S) -> Result<Self, Self::Rejection> {
        Ok(parts
            .extensions
            .get::<ClientIp>()
            .copied()
            .unwrap_or(ClientIp(IpAddr::from([127, 0, 0, 1]))))
    }
}

/// The scheme, host and port the client addressed, after forwarded headers.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RequestOrigin {
    pub scheme: &'static str,
    pub host: String,
    pub port: u16,
}

impl RequestOrigin {
    pub fn is_secure(&self) -> bool {
        self.scheme == "https"
    }

    /// scheme://host[:port], the port omitted when it is the scheme default.
    pub fn base_url(&self) -> String {
        let default = if self.is_secure() { 443 } else { 80 };
        if self.port == default {
            format!("{}://{}", self.scheme, self.host)
        } else {
            format!("{}://{}:{}", self.scheme, self.host, self.port)
        }
    }
}

impl<S: Send + Sync> FromRequestParts<S> for RequestOrigin {
    type Rejection = std::convert::Infallible;

    async fn from_request_parts(parts: &mut Parts, _: &S) -> Result<Self, Self::Rejection> {
        Ok(parts
            .extensions
            .get::<RequestOrigin>()
            .cloned()
            .unwrap_or(RequestOrigin {
                scheme: "http",
                host: "localhost".into(),
                port: 80,
            }))
    }
}

/// Inet6Address.getHostAddress: eight lower-case hextets without leading zeros, no "::".
pub fn java_ip_string(ip: &IpAddr) -> String {
    match ip {
        IpAddr::V4(v4) => v4.to_string(),
        IpAddr::V6(v6) => match v6.to_ipv4_mapped() {
            Some(v4) => v4.to_string(),
            None => v6
                .segments()
                .iter()
                .map(|s| format!("{s:x}"))
                .collect::<Vec<_>>()
                .join(":"),
        },
    }
}

fn header<'a>(headers: &'a HeaderMap, name: &str) -> Option<&'a str> {
    headers.get(name).and_then(|v| v.to_str().ok())
}

fn resolve_client(peer: IpAddr, headers: &HeaderMap, trusted: &TrustedProxies) -> IpAddr {
    let peer = match peer {
        IpAddr::V6(v6) => v6.to_ipv4_mapped().map_or(peer, IpAddr::V4),
        v4 => v4,
    };
    if !trusted.trusts(&peer) {
        return peer;
    }
    let Some(forwarded) = header(headers, "x-forwarded-for") else {
        return peer;
    };
    // RemoteIpValve walks the list from the right, skipping trusted proxies.
    let mut client = peer;
    for entry in forwarded
        .rsplit(',')
        .map(str::trim)
        .filter(|e| !e.is_empty())
    {
        match entry.parse::<IpAddr>() {
            Ok(ip) if trusted.trusts(&ip) => client = ip,
            Ok(ip) => return ip,
            Err(_) => return client,
        }
    }
    client
}

fn valid_port(value: &str) -> Option<u16> {
    if value.is_empty() || value.len() > 5 || !value.bytes().all(|b| b.is_ascii_digit()) {
        return None;
    }
    value.parse::<u16>().ok().filter(|p| *p >= 1)
}

/// Splits host[:port], [v6]:port included.
fn split_host(host: &str) -> (String, Option<&str>) {
    if let Some(rest) = host.strip_prefix('[')
        && let Some((inner, after)) = rest.split_once(']')
    {
        return (format!("[{inner}]"), after.strip_prefix(':'));
    }
    match host.rsplit_once(':') {
        Some((name, port)) if !name.contains(':') => (name.to_owned(), Some(port)),
        _ => (host.to_owned(), None),
    }
}

/// A host a URL can carry: a DNS name, an IPv4 address or a bracketed IPv6 one.
fn is_host_name(name: &str) -> bool {
    match name.strip_prefix('[').and_then(|n| n.strip_suffix(']')) {
        Some(v6) => {
            !v6.is_empty()
                && v6
                    .bytes()
                    .all(|b| b.is_ascii_hexdigit() || b == b':' || b == b'.')
        }
        None => {
            !name.is_empty()
                && name.len() <= 253
                && name
                    .bytes()
                    .all(|b| b.is_ascii_alphanumeric() || matches!(b, b'-' | b'.' | b'_'))
        }
    }
}

fn resolve_origin(headers: &HeaderMap) -> RequestOrigin {
    let proto = header(headers, "x-forwarded-proto");
    let forwarded_host = header(headers, "x-forwarded-host");
    let forwarded_port = header(headers, "x-forwarded-port");
    let host_header = header(headers, "host");
    if proto.is_none() && forwarded_host.is_none() && forwarded_port.is_none() {
        let (host, port) = host_header
            .map(split_host)
            .unwrap_or_else(|| ("localhost".into(), None));
        return RequestOrigin {
            scheme: "http",
            host,
            port: port.and_then(valid_port).unwrap_or(80),
        };
    }
    let https = proto.is_some_and(|p| {
        p.split(',')
            .map(str::trim)
            .all(|v| v.eq_ignore_ascii_case("https"))
    });
    let forwarded = forwarded_host
        .map(split_host)
        .filter(|(name, _)| is_host_name(name));
    let (host, mut port) = match (forwarded, host_header) {
        (Some((name, port)), _) => (name, port.and_then(valid_port)),
        // Tomcat's Host.parse refused a forwarded host no URL can carry (a list appended by a
        // chain of proxies, a path, a quote) and the request kept the name of its Host header.
        // Taken as it came, it failed every same-origin check and reached the issuer, the
        // redirects and WWW-Authenticate.
        (None, Some(h)) => {
            let (name, port) = split_host(h);
            let port = port.filter(|_| forwarded_host.is_none());
            (name, port.and_then(valid_port))
        }
        (None, None) => ("localhost".into(), None),
    };
    if port.is_none() {
        port = forwarded_port.and_then(valid_port);
    }
    RequestOrigin {
        scheme: if https { "https" } else { "http" },
        host,
        port: port.unwrap_or(if https { 443 } else { 80 }),
    }
}

/// Middleware: puts ClientIp and RequestOrigin in the request extensions.
pub async fn resolve(
    axum::extract::State(state): axum::extract::State<crate::AppState>,
    mut request: Request,
    next: axum::middleware::Next,
) -> axum::response::Response {
    let peer = request
        .extensions()
        .get::<ConnectInfo<SocketAddr>>()
        .map(|c| c.0)
        .unwrap_or_else(|| SocketAddr::from(([127, 0, 0, 1], 0)));
    let client = resolve_client(peer.ip(), request.headers(), &state.config.trusted_proxies);
    let origin = resolve_origin(request.headers());
    request.extensions_mut().insert(PeerIp(peer.ip()));
    request.extensions_mut().insert(ClientIp(client));
    request.extensions_mut().insert(origin);
    next.run(request).await
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn prints_ipv6_like_java() {
        assert_eq!(java_ip_string(&"::1".parse().unwrap()), "0:0:0:0:0:0:0:1");
        assert_eq!(java_ip_string(&"10.0.0.7".parse().unwrap()), "10.0.0.7");
    }

    #[test]
    fn forwarded_for_only_from_the_trusted_proxy() {
        let mut headers = HeaderMap::new();
        headers.insert("x-forwarded-for", "203.0.113.9, 10.0.0.2".parse().unwrap());
        let proxy: IpAddr = "10.0.0.2".parse().unwrap();
        assert_eq!(
            resolve_client(proxy, &headers, &TrustedProxies::None),
            proxy
        );
        assert_eq!(
            resolve_client(proxy, &headers, &TrustedProxies::Exact(proxy)),
            "203.0.113.9".parse::<IpAddr>().unwrap()
        );
    }

    #[test]
    fn forwarded_origin_from_any_peer() {
        let mut headers = HeaderMap::new();
        headers.insert("host", "nas:8080".parse().unwrap());
        assert_eq!(resolve_origin(&headers).base_url(), "http://nas:8080");
        headers.insert("x-forwarded-proto", "https".parse().unwrap());
        headers.insert("x-forwarded-host", "picsou.example".parse().unwrap());
        assert_eq!(
            resolve_origin(&headers).base_url(),
            "https://picsou.example"
        );
    }

    #[test]
    fn a_forwarded_host_no_url_can_carry_leaves_the_host_header_in_charge() {
        for forged in [
            "picsou.example, proxy.internal",
            "evil.example/path",
            "evil.example\", error=\"x",
            "evil.example evil",
        ] {
            let mut headers = HeaderMap::new();
            headers.insert("host", "nas:8080".parse().unwrap());
            headers.insert("x-forwarded-proto", "https".parse().unwrap());
            headers.insert("x-forwarded-host", forged.parse().unwrap());
            assert_eq!(
                resolve_origin(&headers).base_url(),
                "https://nas",
                "{forged}"
            );
        }
        let mut headers = HeaderMap::new();
        headers.insert("host", "nas:8080".parse().unwrap());
        headers.insert("x-forwarded-host", "[2001:db8::1]:8443".parse().unwrap());
        assert_eq!(
            resolve_origin(&headers).base_url(),
            "http://[2001:db8::1]:8443"
        );
    }
}
