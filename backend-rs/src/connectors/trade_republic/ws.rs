//! The few RFC 6455 pieces the Trade Republic portfolio read needs: the client handshake over an
//! HTTP/1.1 upgrade and text frames both ways. No WebSocket crate is a dependency.

use std::io;

use base64::Engine;
use base64::engine::general_purpose::STANDARD;
use rand::RngExt;
use sha1::{Digest, Sha1};
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};

const GUID: &str = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
/// A frame larger than this is refused: a portfolio answer is a few kilobytes.
const MAX_MESSAGE_BYTES: usize = 16 * 1024 * 1024;

pub const OP_CONTINUATION: u8 = 0x0;
pub const OP_TEXT: u8 = 0x1;
pub const OP_BINARY: u8 = 0x2;
pub const OP_CLOSE: u8 = 0x8;
pub const OP_PING: u8 = 0x9;
pub const OP_PONG: u8 = 0xA;

/// The Sec-WebSocket-Accept a server answers `key` with.
pub fn accept_key(key: &str) -> String {
    let mut sha = Sha1::new();
    sha.update(key.as_bytes());
    sha.update(GUID.as_bytes());
    STANDARD.encode(sha.finalize())
}

/// A fresh Sec-WebSocket-Key.
pub fn new_key() -> String {
    let nonce: [u8; 16] = rand::rng().random();
    STANDARD.encode(nonce)
}

/// Writes one final frame; a client masks it, a server does not.
pub async fn write_frame<W: AsyncWrite + Unpin>(
    io: &mut W,
    opcode: u8,
    payload: &[u8],
    masked: bool,
) -> io::Result<()> {
    let mut frame = Vec::with_capacity(payload.len() + 14);
    frame.push(0x80 | opcode);
    let mask_bit = if masked { 0x80 } else { 0 };
    match payload.len() {
        len @ 0..=125 => frame.push(mask_bit | len as u8),
        len @ 126..=0xFFFF => {
            frame.push(mask_bit | 126);
            frame.extend_from_slice(&(len as u16).to_be_bytes());
        }
        len => {
            frame.push(mask_bit | 127);
            frame.extend_from_slice(&(len as u64).to_be_bytes());
        }
    }
    if masked {
        let mask: [u8; 4] = rand::rng().random();
        frame.extend_from_slice(&mask);
        frame.extend(payload.iter().enumerate().map(|(i, b)| b ^ mask[i % 4]));
    } else {
        frame.extend_from_slice(payload);
    }
    io.write_all(&frame).await?;
    io.flush().await
}

/// One message as received: a text (fragments joined), or a control frame.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Message {
    Text(String),
    Binary(Vec<u8>),
    Ping(Vec<u8>),
    Pong,
    Close,
}

async fn read_frame<R: AsyncRead + Unpin>(io: &mut R) -> io::Result<(bool, u8, Vec<u8>)> {
    let mut head = [0u8; 2];
    io.read_exact(&mut head).await?;
    let fin = head[0] & 0x80 != 0;
    let opcode = head[0] & 0x0F;
    let masked = head[1] & 0x80 != 0;
    let len = match head[1] & 0x7F {
        126 => u64::from(io.read_u16().await?),
        127 => io.read_u64().await?,
        len => u64::from(len),
    };
    let len = usize::try_from(len)
        .ok()
        .filter(|len| *len <= MAX_MESSAGE_BYTES)
        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "WebSocket frame too large"))?;
    let mut mask = [0u8; 4];
    if masked {
        io.read_exact(&mut mask).await?;
    }
    let mut payload = vec![0u8; len];
    io.read_exact(&mut payload).await?;
    if masked {
        for (i, byte) in payload.iter_mut().enumerate() {
            *byte ^= mask[i % 4];
        }
    }
    Ok((fin, opcode, payload))
}

/// The next message, fragments reassembled.
pub async fn read_message<R: AsyncRead + Unpin>(io: &mut R) -> io::Result<Message> {
    let mut data: Vec<u8> = Vec::new();
    let mut kind: Option<u8> = None;
    loop {
        let (fin, opcode, payload) = read_frame(io).await?;
        match opcode {
            OP_PING => return Ok(Message::Ping(payload)),
            OP_PONG => return Ok(Message::Pong),
            OP_CLOSE => return Ok(Message::Close),
            OP_TEXT | OP_BINARY if kind.is_none() => kind = Some(opcode),
            OP_CONTINUATION if kind.is_some() => {}
            _ => {
                return Err(io::Error::new(
                    io::ErrorKind::InvalidData,
                    "unexpected WebSocket frame",
                ));
            }
        }
        if data.len() + payload.len() > MAX_MESSAGE_BYTES {
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                "WebSocket message too large",
            ));
        }
        data.extend_from_slice(&payload);
        if fin {
            return Ok(if kind == Some(OP_TEXT) {
                Message::Text(String::from_utf8(data).map_err(|_| {
                    io::Error::new(io::ErrorKind::InvalidData, "WebSocket text is not UTF-8")
                })?)
            } else {
                Message::Binary(data)
            });
        }
    }
}

/// A client connection: texts in and out, pings answered.
pub struct Client<S> {
    io: S,
}

impl<S: AsyncRead + AsyncWrite + Unpin> Client<S> {
    pub fn new(io: S) -> Self {
        Self { io }
    }

    pub async fn send_text(&mut self, text: &str) -> io::Result<()> {
        write_frame(&mut self.io, OP_TEXT, text.as_bytes(), true).await
    }

    /// The next text message; None once the server closed the connection.
    pub async fn next_text(&mut self) -> io::Result<Option<String>> {
        loop {
            match read_message(&mut self.io).await? {
                Message::Text(text) => return Ok(Some(text)),
                Message::Ping(payload) => {
                    write_frame(&mut self.io, OP_PONG, &payload, true).await?;
                }
                Message::Close => return Ok(None),
                Message::Binary(_) | Message::Pong => {}
            }
        }
    }
}

/// Opens a WebSocket through `http` (ws:// or wss://), with the extra handshake headers.
pub async fn connect(
    http: &reqwest::Client,
    url: &str,
    headers: &[(&str, &str)],
) -> io::Result<Client<reqwest::Upgraded>> {
    let target = if let Some(rest) = url.strip_prefix("wss://") {
        format!("https://{rest}")
    } else if let Some(rest) = url.strip_prefix("ws://") {
        format!("http://{rest}")
    } else {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "not a WebSocket URL",
        ));
    };
    let key = new_key();
    let mut request = http
        .get(target)
        .header(http::header::CONNECTION, "Upgrade")
        .header(http::header::UPGRADE, "websocket")
        .header(http::header::SEC_WEBSOCKET_VERSION, "13")
        .header(http::header::SEC_WEBSOCKET_KEY, &key);
    for (name, value) in headers {
        request = request.header(*name, *value);
    }
    let response = request.send().await.map_err(io::Error::other)?;
    let accepted = response
        .headers()
        .get(http::header::SEC_WEBSOCKET_ACCEPT)
        .and_then(|v| v.to_str().ok())
        .is_some_and(|v| v == accept_key(&key));
    if response.status() != http::StatusCode::SWITCHING_PROTOCOLS || !accepted {
        return Err(io::Error::other(format!(
            "WebSocket handshake refused (status {})",
            response.status().as_u16()
        )));
    }
    Ok(Client::new(
        response.upgrade().await.map_err(io::Error::other)?,
    ))
}
