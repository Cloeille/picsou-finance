//! The subset of java.net.URI's parser that SimplefinUrls relies on: which strings parse, and
//! the scheme, raw user info, host, port, path and query they yield. A URL the Java backend
//! refuses as malformed must be refused here with the same message, and one it parses must
//! yield the same host for the allowlist.

/// A parsed URI. host is None for an opaque URI or a registry-based authority, as getHost().
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct JavaUri {
    pub scheme: Option<String>,
    pub raw_user_info: Option<String>,
    pub host: Option<String>,
    /// -1 when absent, as getPort().
    pub port: i32,
    pub raw_path: Option<String>,
    pub raw_query: Option<String>,
}

/// URISyntaxException; it carries nothing, since Java's message quotes the input.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Malformed;

type Parsed<T> = Result<T, Malformed>;

#[derive(Clone, Copy)]
enum Class {
    Uric,
    Path,
    UserInfo,
    RegName,
    Server,
    Scheme,
    Alpha,
    Digit,
    Alnum,
    AlnumDash,
    DigitDot,
}

fn is_mark(c: char) -> bool {
    "-_.!~*'()".contains(c)
}

fn is_unreserved(c: char) -> bool {
    c.is_ascii_alphanumeric() || is_mark(c)
}

impl Class {
    /// URI.match: ASCII only; NUL never matches.
    fn matches(self, c: char) -> bool {
        if c == '\0' || !c.is_ascii() {
            return false;
        }
        match self {
            Class::Uric => is_unreserved(c) || ";/?:@&=+$,[]".contains(c),
            Class::Path => is_unreserved(c) || ":@&=+$,;/".contains(c),
            Class::UserInfo => is_unreserved(c) || ";:&=+$,".contains(c),
            Class::RegName => is_unreserved(c) || "$,;:@&=+".contains(c),
            Class::Server => is_unreserved(c) || ";:&=+$,.:@[]-".contains(c),
            Class::Scheme => c.is_ascii_alphanumeric() || "+-.".contains(c),
            Class::Alpha => c.is_ascii_alphabetic(),
            Class::Digit => c.is_ascii_digit(),
            Class::Alnum => c.is_ascii_alphanumeric(),
            Class::AlnumDash => c.is_ascii_alphanumeric() || c == '-',
            Class::DigitDot => c.is_ascii_digit() || c == '.',
        }
    }

    /// Whether the class admits escaped octets and visible non-ASCII characters (L_ESCAPED).
    fn escaped(self) -> bool {
        matches!(
            self,
            Class::Uric | Class::Path | Class::UserInfo | Class::RegName | Class::Server
        )
    }
}

/// Character.isSpaceChar (Zs, Zl, Zp) or Character.isISOControl.
fn is_space_or_control(c: char) -> bool {
    matches!(
        c,
        '\u{0}'..='\u{1F}'
            | '\u{7F}'..='\u{9F}'
            | ' '
            | '\u{A0}'
            | '\u{1680}'
            | '\u{2000}'..='\u{200A}'
            | '\u{2028}'
            | '\u{2029}'
            | '\u{202F}'
            | '\u{205F}'
            | '\u{3000}'
    )
}

struct Parser {
    input: Vec<char>,
    uri: JavaUri,
}

/// new URI(input).
pub fn parse(input: &str) -> Parsed<JavaUri> {
    let mut parser = Parser {
        input: input.chars().collect(),
        uri: JavaUri {
            port: -1,
            ..JavaUri::default()
        },
    };
    parser.parse()?;
    Ok(parser.uri)
}

impl Parser {
    fn at(&self, p: usize, n: usize, c: char) -> bool {
        p < n && self.input[p] == c
    }

    fn text(&self, from: usize, to: usize) -> String {
        self.input[from..to].iter().collect()
    }

    /// scan(start, end, err, stop): None when an err char comes first, else the index of the
    /// first stop char or the end.
    fn scan_until(&self, start: usize, end: usize, err: &str, stop: &str) -> Option<usize> {
        let mut p = start;
        while p < end {
            let c = self.input[p];
            if err.contains(c) {
                return None;
            }
            if stop.contains(c) {
                break;
            }
            p += 1;
        }
        Some(p)
    }

    fn scan_stop(&self, start: usize, end: usize, stop: &str) -> usize {
        self.scan_until(start, end, "", stop).unwrap_or(end)
    }

    /// scan(start, end, char): past the char when it is at start.
    fn scan_char(&self, start: usize, end: usize, c: char) -> usize {
        if self.at(start, end, c) {
            start + 1
        } else {
            start
        }
    }

    /// scan(start, end, lowMask, highMask), which throws on a malformed escape pair.
    fn scan_class(&self, start: usize, n: usize, class: Class) -> Parsed<usize> {
        let mut p = start;
        while p < n {
            let c = self.input[p];
            if class.matches(c) {
                p += 1;
                continue;
            }
            if class.escaped() {
                if c == '%' {
                    let hex = |i: usize| i < n && self.input[i].is_ascii_hexdigit();
                    if hex(p + 1) && hex(p + 2) {
                        p += 3;
                        continue;
                    }
                    return Err(Malformed);
                }
                if u32::from(c) > 128 && !is_space_or_control(c) {
                    p += 1;
                    continue;
                }
            }
            break;
        }
        Ok(p)
    }

    fn check_chars(&self, start: usize, end: usize, class: Class) -> Parsed<()> {
        if self.scan_class(start, end, class)? < end {
            return Err(Malformed);
        }
        Ok(())
    }

    fn parse(&mut self) -> Parsed<()> {
        let n = self.input.len();
        let colon = self.scan_until(0, n, "/?#", ":");
        let mut p;
        match colon {
            Some(c) if self.at(c, n, ':') => {
                if c == 0 || !Class::Alpha.matches(self.input[0]) {
                    return Err(Malformed);
                }
                self.check_chars(1, c, Class::Scheme)?;
                self.uri.scheme = Some(self.text(0, c));
                p = c + 1;
                if self.at(p, n, '/') {
                    p = self.parse_hierarchical(p, n)?;
                } else {
                    let q = self.scan_stop(p, n, "#");
                    if q <= p {
                        return Err(Malformed);
                    }
                    self.check_chars(p, q, Class::Uric)?;
                    p = q;
                }
            }
            _ => p = self.parse_hierarchical(0, n)?,
        }
        if self.at(p, n, '#') {
            self.check_chars(p + 1, n, Class::Uric)?;
            p = n;
        }
        if p < n {
            return Err(Malformed);
        }
        Ok(())
    }

    fn parse_hierarchical(&mut self, start: usize, n: usize) -> Parsed<usize> {
        let mut p = start;
        if self.at(p, n, '/') && self.at(p + 1, n, '/') {
            p += 2;
            let q = self.scan_stop(p, n, "/?#");
            if q > p {
                p = self.parse_authority(p, q)?;
            } else if q >= n {
                return Err(Malformed);
            }
        }
        let q = self.scan_stop(p, n, "?#");
        self.check_chars(p, q, Class::Path)?;
        self.uri.raw_path = Some(self.text(p, q));
        p = q;
        if self.at(p, n, '?') {
            p += 1;
            let q = self.scan_stop(p, n, "#");
            self.check_chars(p, q, Class::Uric)?;
            self.uri.raw_query = Some(self.text(p, q));
            p = q;
        }
        Ok(p)
    }

    fn parse_authority(&mut self, start: usize, n: usize) -> Parsed<usize> {
        let server_chars = self.scan_class(start, n, Class::Server)? == n;
        let reg_chars = self.scan_class(start, n, Class::RegName)? == n;
        if reg_chars && !server_chars {
            return Ok(n);
        }
        let mut failure = None;
        let mut q = start;
        if server_chars {
            match self.parse_server(start, n) {
                Ok(end) if end >= n => q = end,
                Ok(_) | Err(_) => {
                    self.uri.raw_user_info = None;
                    self.uri.host = None;
                    self.uri.port = -1;
                    failure = Some(Malformed);
                }
            }
        }
        if q < n || failure.is_some() {
            if reg_chars {
                return Ok(n);
            }
            return Err(Malformed);
        }
        Ok(n)
    }

    fn parse_server(&mut self, start: usize, n: usize) -> Parsed<usize> {
        let mut p = start;
        if let Some(q) = self.scan_until(p, n, "/?#", "@")
            && self.at(q, n, '@')
        {
            self.check_chars(p, q, Class::UserInfo)?;
            self.uri.raw_user_info = Some(self.text(p, q));
            p = q + 1;
        }
        if self.at(p, n, '[') {
            p += 1;
            let q = self.scan_until(p, n, "/?#", "]").ok_or(Malformed)?;
            if q <= p || !self.at(q, n, ']') {
                return Err(Malformed);
            }
            let literal = self.text(p, q);
            let address = match literal.split_once('%') {
                Some((_, "")) => return Err(Malformed),
                Some((address, _)) => address,
                None => literal.as_str(),
            };
            if address.parse::<std::net::Ipv6Addr>().is_err() {
                return Err(Malformed);
            }
            self.uri.host = Some(self.text(p - 1, q + 1));
            p = q + 1;
        } else {
            let q = self.parse_ipv4(p, n);
            p = match q {
                Some(q) if q > p => q,
                _ => self.parse_hostname(p, n)?,
            };
        }
        if self.at(p, n, ':') {
            p += 1;
            let q = self.scan_class(p, n, Class::Digit)?;
            if q > p {
                let digits = self.text(p, q);
                self.uri.port = digits.parse::<i32>().map_err(|_| Malformed)?;
                p = q;
            }
        }
        if p < n {
            return Err(Malformed);
        }
        Ok(p)
    }

    /// parseIPv4Address: the end of a dotted quad, or None.
    fn parse_ipv4(&mut self, start: usize, n: usize) -> Option<usize> {
        let m = self.scan_class(start, n, Class::DigitDot).ok()?;
        if m <= start {
            return None;
        }
        let mut p = start;
        for i in 0..4 {
            let q = self.scan_byte(p, m)?;
            if q <= p {
                return None;
            }
            p = q;
            if i < 3 {
                let q = self.scan_char(p, m, '.');
                if q <= p {
                    return None;
                }
                p = q;
            }
        }
        if p < m {
            return None;
        }
        if p < n && self.input[p] != ':' {
            return None;
        }
        self.uri.host = Some(self.text(start, p));
        Some(p)
    }

    fn scan_byte(&self, start: usize, n: usize) -> Option<usize> {
        let q = self.scan_class(start, n, Class::Digit).ok()?;
        if q <= start {
            return Some(q);
        }
        match self.text(start, q).parse::<u32>() {
            Ok(v) if v <= 255 => Some(q),
            Ok(_) => Some(start),
            Err(_) => None,
        }
    }

    fn parse_hostname(&mut self, start: usize, n: usize) -> Parsed<usize> {
        let mut p = start;
        let mut last_label = None;
        loop {
            let q = self.scan_class(p, n, Class::Alnum)?;
            if q <= p {
                break;
            }
            last_label = Some(p);
            p = q;
            let q = self.scan_class(p, n, Class::AlnumDash)?;
            if q > p {
                if self.input[q - 1] == '-' {
                    return Err(Malformed);
                }
                p = q;
            }
            let q = self.scan_char(p, n, '.');
            if q <= p {
                break;
            }
            p = q;
            if p >= n {
                break;
            }
        }
        if p < n && !self.at(p, n, ':') {
            return Err(Malformed);
        }
        let Some(label) = last_label else {
            return Err(Malformed);
        };
        if label > start && !Class::Alpha.matches(self.input[label]) {
            return Err(Malformed);
        }
        self.uri.host = Some(self.text(start, p));
        Ok(p)
    }
}

/// Percent-decodes as URI.decode does: escaped octets read as UTF-8, malformed bytes replaced.
pub fn percent_decode_lossy(value: &str) -> String {
    let bytes = value.as_bytes();
    let mut out = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i] == b'%'
            && i + 2 < bytes.len()
            && let (Some(h), Some(l)) = (hex_value(bytes[i + 1]), hex_value(bytes[i + 2]))
        {
            out.push(h * 16 + l);
            i += 3;
            continue;
        }
        out.push(bytes[i]);
        i += 1;
    }
    String::from_utf8_lossy(&out).into_owned()
}

fn hex_value(b: u8) -> Option<u8> {
    char::from(b)
        .to_digit(16)
        .and_then(|d| u8::try_from(d).ok())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn host(input: &str) -> Option<String> {
        parse(input).ok().and_then(|u| u.host)
    }

    #[test]
    fn a_plain_https_url_yields_its_parts() {
        let uri = parse("https://u:p@beta-bridge.simplefin.org:443/simplefin?x=1#f").unwrap();
        assert_eq!(uri.scheme.as_deref(), Some("https"));
        assert_eq!(uri.raw_user_info.as_deref(), Some("u:p"));
        assert_eq!(uri.host.as_deref(), Some("beta-bridge.simplefin.org"));
        assert_eq!(uri.port, 443);
        assert_eq!(uri.raw_path.as_deref(), Some("/simplefin"));
        assert_eq!(uri.raw_query.as_deref(), Some("x=1"));
    }

    #[test]
    fn hosts_follow_java() {
        assert_eq!(host("https://10.0.0.5/x").as_deref(), Some("10.0.0.5"));
        assert_eq!(host("https://2130706433/x").as_deref(), Some("2130706433"));
        assert_eq!(host("https://[::1]/x").as_deref(), Some("[::1]"));
        assert_eq!(host("https://a.b.c./x").as_deref(), Some("a.b.c."));
        // A registry-based authority has no host.
        assert_eq!(host("https://u:p@a@b/x"), None);
        assert_eq!(host("https://beta%2Dbridge.simplefin.org/x"), None);
        assert_eq!(host("https:beta-bridge.simplefin.org/x"), None);
        assert_eq!(host("https:///beta-bridge.simplefin.org/x"), None);
        // A port past an int falls back to a registry-based authority, as in Java.
        assert_eq!(host("https://host:99999999999/x"), None);
    }

    #[test]
    fn malformed_input_is_refused() {
        for input in [
            "https://evil.example\\@beta-bridge.simplefin.org/x",
            "https://beta-bridge.simplefin.org/a b",
            "https://beta-bridge.simplefin.org/%zz",
            "1https://x",
            ":x",
            "https://[zz]/x",
        ] {
            assert_eq!(parse(input), Err(Malformed), "{input}");
        }
    }

    #[test]
    fn percent_decoding_reads_utf8_and_replaces_bad_bytes() {
        assert_eq!(percent_decode_lossy("p%2Bss%C3%A9"), "p+ssé");
        assert_eq!(percent_decode_lossy("%FF"), "\u{FFFD}");
        assert_eq!(percent_decode_lossy("100%"), "100%");
    }
}
