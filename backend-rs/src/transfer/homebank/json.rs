//! A pull parser with the strictness and limits of the Jackson factory HomeBankFileParser
//! builds: standard JSON only, duplicate names rejected in every object, nesting 64, names 256
//! chars, numbers 64 chars, 2,000,000 tokens. A string's 4096-char limit applies when it is
//! read, not when it is skipped, as Jackson only checks text it materializes.

use std::collections::HashSet;

use super::source::jlen;

const MAX_DEPTH: usize = 64;
const MAX_NAME_CHARS: usize = 256;
const MAX_NUMBER_CHARS: usize = 64;
const MAX_TOKENS: usize = 2_000_000;
pub const MAX_STRING_CHARS: usize = 4096;

/// Any syntax or limit failure: the caller reports the generic corrupted-file message.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Syntax;

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Token {
    StartObject,
    EndObject,
    StartArray,
    EndArray,
    Field(String),
    Str(String),
    Int(String),
    Float(String),
    True,
    False,
    Null,
}

impl Token {
    pub fn is_start(&self) -> bool {
        matches!(self, Token::StartObject | Token::StartArray)
    }
}

enum Ctx {
    Object {
        names: HashSet<String>,
        has_items: bool,
        need_value: bool,
    },
    Array {
        has_items: bool,
    },
}

pub struct Parser<'a> {
    src: &'a [u8],
    pos: usize,
    stack: Vec<Ctx>,
    tokens: usize,
}

impl<'a> Parser<'a> {
    pub fn new(src: &'a [u8]) -> Self {
        let src = src.strip_prefix(b"\xEF\xBB\xBF").unwrap_or(src);
        Self {
            src,
            pos: 0,
            stack: Vec::new(),
            tokens: 0,
        }
    }

    fn peek(&self) -> Option<u8> {
        self.src.get(self.pos).copied()
    }

    fn skip_whitespace(&mut self) {
        while matches!(self.peek(), Some(b' ' | b'\t' | b'\n' | b'\r')) {
            self.pos += 1;
        }
    }

    fn expect(&mut self, byte: u8) -> Result<(), Syntax> {
        self.skip_whitespace();
        if self.peek() == Some(byte) {
            self.pos += 1;
            Ok(())
        } else {
            Err(Syntax)
        }
    }

    /// The next token, None at the end of the input (between root values only).
    pub fn next_token(&mut self) -> Result<Option<Token>, Syntax> {
        let token = self.advance()?;
        if token.is_some() {
            self.tokens += 1;
            if self.tokens > MAX_TOKENS {
                return Err(Syntax);
            }
        }
        Ok(token)
    }

    fn advance(&mut self) -> Result<Option<Token>, Syntax> {
        self.skip_whitespace();
        match self.stack.last_mut() {
            None => {
                if self.pos >= self.src.len() {
                    return Ok(None);
                }
                self.value().map(Some)
            }
            Some(Ctx::Array { has_items }) => {
                if self.src.get(self.pos) == Some(&b']') {
                    self.pos += 1;
                    self.pop();
                    return Ok(Some(Token::EndArray));
                }
                if *has_items {
                    self.expect(b',')?;
                } else {
                    *has_items = true;
                }
                self.value().map(Some)
            }
            Some(Ctx::Object {
                need_value: true, ..
            }) => {
                if let Some(Ctx::Object { need_value, .. }) = self.stack.last_mut() {
                    *need_value = false;
                }
                self.value().map(Some)
            }
            Some(Ctx::Object { has_items, .. }) => {
                if self.src.get(self.pos) == Some(&b'}') {
                    self.pos += 1;
                    self.pop();
                    return Ok(Some(Token::EndObject));
                }
                if *has_items {
                    self.expect(b',')?;
                    self.skip_whitespace();
                }
                if self.peek() != Some(b'"') {
                    return Err(Syntax);
                }
                self.pos += 1;
                let name = self.string()?;
                if jlen(&name) > MAX_NAME_CHARS {
                    return Err(Syntax);
                }
                self.expect(b':')?;
                let Some(Ctx::Object {
                    names,
                    has_items,
                    need_value,
                }) = self.stack.last_mut()
                else {
                    return Err(Syntax);
                };
                if !names.insert(name.clone()) {
                    return Err(Syntax);
                }
                *has_items = true;
                *need_value = true;
                Ok(Some(Token::Field(name)))
            }
        }
    }

    fn pop(&mut self) {
        self.stack.pop();
    }

    fn push(&mut self, ctx: Ctx) -> Result<(), Syntax> {
        if self.stack.len() + 1 > MAX_DEPTH {
            return Err(Syntax);
        }
        self.stack.push(ctx);
        Ok(())
    }

    fn value(&mut self) -> Result<Token, Syntax> {
        self.skip_whitespace();
        let byte = self.peek().ok_or(Syntax)?;
        match byte {
            b'{' => {
                self.pos += 1;
                self.push(Ctx::Object {
                    names: HashSet::new(),
                    has_items: false,
                    need_value: false,
                })?;
                Ok(Token::StartObject)
            }
            b'[' => {
                self.pos += 1;
                self.push(Ctx::Array { has_items: false })?;
                Ok(Token::StartArray)
            }
            b'"' => {
                self.pos += 1;
                self.string().map(Token::Str)
            }
            b't' => self.literal("true", Token::True),
            b'f' => self.literal("false", Token::False),
            b'n' => self.literal("null", Token::Null),
            b'-' | b'0'..=b'9' => self.number(),
            _ => Err(Syntax),
        }
    }

    fn literal(&mut self, word: &str, token: Token) -> Result<Token, Syntax> {
        let end = self.pos + word.len();
        if self.src.get(self.pos..end) != Some(word.as_bytes()) {
            return Err(Syntax);
        }
        if self
            .src
            .get(end)
            .is_some_and(|b| b.is_ascii_alphanumeric() || matches!(b, b'_' | b'$') || *b >= 0x80)
        {
            return Err(Syntax);
        }
        self.pos = end;
        Ok(token)
    }

    fn number(&mut self) -> Result<Token, Syntax> {
        let start = self.pos;
        if self.peek() == Some(b'-') {
            self.pos += 1;
        }
        let digits = |p: &mut Self| {
            let from = p.pos;
            while p.peek().is_some_and(|b| b.is_ascii_digit()) {
                p.pos += 1;
            }
            p.pos - from
        };
        let int_start = self.pos;
        let int_digits = digits(self);
        if int_digits == 0 || (int_digits > 1 && self.src[int_start] == b'0') {
            return Err(Syntax);
        }
        let mut float = false;
        if self.peek() == Some(b'.') {
            self.pos += 1;
            float = true;
            if digits(self) == 0 {
                return Err(Syntax);
            }
        }
        if matches!(self.peek(), Some(b'e' | b'E')) {
            self.pos += 1;
            float = true;
            if matches!(self.peek(), Some(b'+' | b'-')) {
                self.pos += 1;
            }
            if digits(self) == 0 {
                return Err(Syntax);
            }
        }
        let text = std::str::from_utf8(&self.src[start..self.pos]).map_err(|_| Syntax)?;
        if text.trim_start_matches('-').len() > MAX_NUMBER_CHARS {
            return Err(Syntax);
        }
        Ok(if float {
            Token::Float(text.to_owned())
        } else {
            Token::Int(text.to_owned())
        })
    }

    /// The rest of a string after its opening quote.
    fn string(&mut self) -> Result<String, Syntax> {
        let mut out = Vec::new();
        loop {
            let byte = self.peek().ok_or(Syntax)?;
            self.pos += 1;
            match byte {
                b'"' => break,
                b'\\' => {
                    let escape = self.peek().ok_or(Syntax)?;
                    self.pos += 1;
                    let simple = match escape {
                        b'"' => Some(b'"'),
                        b'\\' => Some(b'\\'),
                        b'/' => Some(b'/'),
                        b'b' => Some(0x08),
                        b'f' => Some(0x0c),
                        b'n' => Some(b'\n'),
                        b'r' => Some(b'\r'),
                        b't' => Some(b'\t'),
                        b'u' => None,
                        _ => return Err(Syntax),
                    };
                    match simple {
                        Some(b) => out.push(b),
                        None => {
                            let unit = self.hex4()?;
                            let ch = if (0xD800..0xDC00).contains(&unit)
                                && self.src.get(self.pos..self.pos + 2) == Some(b"\\u")
                            {
                                let saved = self.pos;
                                self.pos += 2;
                                let low = self.hex4()?;
                                if (0xDC00..0xE000).contains(&low) {
                                    char::from_u32(
                                        0x10000 + ((unit - 0xD800) << 10) + (low - 0xDC00),
                                    )
                                } else {
                                    self.pos = saved;
                                    None
                                }
                            } else {
                                char::from_u32(unit)
                            };
                            let mut buf = [0u8; 4];
                            out.extend_from_slice(
                                ch.unwrap_or('\u{FFFD}').encode_utf8(&mut buf).as_bytes(),
                            );
                        }
                    }
                }
                0x00..=0x1f => return Err(Syntax),
                other => out.push(other),
            }
        }
        String::from_utf8(out).map_err(|_| Syntax)
    }

    fn hex4(&mut self) -> Result<u32, Syntax> {
        let hex = self.src.get(self.pos..self.pos + 4).ok_or(Syntax)?;
        let text = std::str::from_utf8(hex).map_err(|_| Syntax)?;
        if !text.bytes().all(|b| b.is_ascii_hexdigit()) {
            return Err(Syntax);
        }
        self.pos += 4;
        u32::from_str_radix(text, 16).map_err(|_| Syntax)
    }

    /// JsonParser.skipChildren: after a start token, consumes up to its matching end.
    pub fn skip_children(&mut self, current: &Token) -> Result<(), Syntax> {
        if !current.is_start() {
            return Ok(());
        }
        let depth = self.stack.len();
        while self.stack.len() >= depth {
            if self.next_token()?.is_none() {
                return Err(Syntax);
            }
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn tokens(src: &str) -> Result<Vec<Token>, Syntax> {
        let mut parser = Parser::new(src.as_bytes());
        let mut out = Vec::new();
        while let Some(token) = parser.next_token()? {
            out.push(token);
        }
        Ok(out)
    }

    #[test]
    fn reads_standard_json() {
        assert_eq!(
            tokens(r#"{"a":[1,-0.5e2,"x\u00e9\ud83d\ude00",true,null],"b":{}}"#).unwrap(),
            vec![
                Token::StartObject,
                Token::Field("a".into()),
                Token::StartArray,
                Token::Int("1".into()),
                Token::Float("-0.5e2".into()),
                Token::Str("x\u{e9}\u{1F600}".into()),
                Token::True,
                Token::Null,
                Token::EndArray,
                Token::Field("b".into()),
                Token::StartObject,
                Token::EndObject,
                Token::EndObject,
            ]
        );
    }

    #[test]
    fn rejects_what_jackson_rejects() {
        for bad in [
            r#"{"a":1,"a":2}"#,
            r#"{"a":01}"#,
            r#"{"a":1.}"#,
            r#"{"a":.5}"#,
            r#"{"a":+1}"#,
            r#"{"a":[1,]}"#,
            r#"{"a":1,}"#,
            r#"{"a":"\x"}"#,
            "{\"a\":\"\u{1}\"}",
            r#"{"a":truex}"#,
            r#"{'a':1}"#,
            r#"{"a" 1}"#,
        ] {
            assert_eq!(tokens(bad), Err(Syntax), "{bad}");
        }
        let deep = format!("{}{}", "[".repeat(65), "]".repeat(65));
        assert_eq!(tokens(&deep), Err(Syntax));
        let ok = format!("{}{}", "[".repeat(64), "]".repeat(64));
        assert!(tokens(&ok).is_ok());
    }
}
