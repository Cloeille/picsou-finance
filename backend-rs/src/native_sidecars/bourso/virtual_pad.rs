//! Decodes BoursoBank's virtual keyboard into a digit -> key-code mapping.
//!
//! Faithful port of `services/bourso-auth/virtual_pad.py`:
//! - Each button carries a rotating 3-letter `data-matrix-key` and a base64 SVG data URI.
//! - Digits are recovered strictly by SHA-256 of the base64 payload (whitespace stripped).
//! - Never guesses: missing, unknown, duplicated digits or reused key codes fail closed.

use std::collections::{HashMap, HashSet};
use std::sync::LazyLock;

use regex::Regex;
use sha2::{Digest, Sha256};

const SVG_DIGEST_TO_DIGIT: [(&str, usize); 10] = [
    (
        "8560081e18568aba02ef3b1f7ac0e8b238cbbd21b70a5e919360ac456d45d506",
        0,
    ),
    (
        "eadac6d6288cbd61524fd1a3078a19bf555735c7af13a2890e307263c4c7259b",
        1,
    ),
    (
        "c54018639480788c02708b2d89651627dadf74048e029844f92006e19eadc094",
        2,
    ),
    (
        "f3022aeced3b8f45f69c1ec001909636984c81b7e5fcdc2bc481668b1e84ae05",
        3,
    ),
    (
        "3e3d48446781f9f337858e56d01dd9a66c6be697ba34d8f63f48e694f755a480",
        4,
    ),
    (
        "4b16fb3592febdd9fb794dc52e4d49f5713e9af05486388f3ca259226dcd5cce",
        5,
    ),
    (
        "9b3afcc0ceb68c70cc697330d8a609900cf330b6aef1fb102f7a1c34cd8bc3d4",
        6,
    ),
    (
        "9e760193de1b6c5135ebe1bcad7ff65a2aacfc318973ce29ecb23ed2f86d6012",
        7,
    ),
    (
        "64d87d9a7023788e21591679c1783390011575a189ea82bb36776a096c7ca02c",
        8,
    ),
    (
        "1b358233ad4eb6b10bf0dadc3404261317a1b78b62f8501b70c646d654ae88f1",
        9,
    ),
];

static BUTTON_RE: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r#"(?s)<button[^>]*?data-matrix-key="(?P<key>[A-Z]{3})"[^>]*?>.*?src="(?P<svg>data:image[^"]*)""#)
        .expect("button regex")
});

static CHALLENGE_SCRIPT_RE: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r#"data-matrix-random-challenge\]"\)\.val\("(?P<challenge>.*?)"\)"#)
        .expect("challenge script regex")
});

static CHALLENGE_ATTR_RE: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r#"data-matrix-random-challenge="(?P<challenge>[^"]+)""#)
        .expect("challenge attr regex")
});

static DATA_URI_PREFIX_RE: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"(?i)^data:image/[^;]+;base64,").expect("data uri prefix regex"));

#[derive(Debug, Clone, thiserror::Error)]
#[error("{0}")]
pub struct VirtualPadError(pub String);

/// SHA-256 hex digest of a button image's base64 payload, whitespace-insensitive.
pub fn svg_digest(data_uri: &str) -> String {
    let trimmed = data_uri.trim();
    let payload = DATA_URI_PREFIX_RE.replace(trimmed, "");
    let compact: String = payload.split_whitespace().collect();
    let digest = Sha256::digest(compact.as_bytes());
    hex::encode(digest)
}

/// Return the ten key codes indexed by the digit they stand for (0..=9).
pub fn parse_virtual_pad(html: &str) -> Result<[String; 10], VirtualPadError> {
    let mut by_digit: HashMap<usize, String> = HashMap::with_capacity(10);

    for cap in BUTTON_RE.captures_iter(html) {
        let key = cap.name("key").expect("key capture").as_str().to_owned();
        let svg = cap.name("svg").expect("svg capture").as_str();
        let digest = svg_digest(svg);
        let Some(&(_, digit)) = SVG_DIGEST_TO_DIGIT.iter().find(|&&(d, _)| d == digest) else {
            return Err(VirtualPadError(
                "Unrecognised virtual-keyboard image -- BoursoBank changed its login page"
                    .to_owned(),
            ));
        };
        if by_digit.contains_key(&digit) {
            return Err(VirtualPadError(format!(
                "Digit {digit} appears twice on the virtual keyboard"
            )));
        }
        by_digit.insert(digit, key);
    }

    let missing: Vec<String> = (0..10)
        .filter(|d| !by_digit.contains_key(d))
        .map(|d| d.to_string())
        .collect();
    if !missing.is_empty() {
        return Err(VirtualPadError(format!(
            "Virtual keyboard is missing digits {} ({} of 10 buttons decoded)",
            missing.join(","),
            by_digit.len()
        )));
    }

    let distinct: HashSet<&str> = by_digit.values().map(String::as_str).collect();
    if distinct.len() != 10 {
        return Err(VirtualPadError(
            "Virtual keyboard reused a key code across digits".to_owned(),
        ));
    }

    Ok(std::array::from_fn(|d| by_digit.remove(&d).expect("digit")))
}

/// Extract the per-session `matrixRandomChallenge` from the virtual keyboard HTML.
pub fn extract_challenge(html: &str) -> Result<String, VirtualPadError> {
    for pattern in [&*CHALLENGE_SCRIPT_RE, &*CHALLENGE_ATTR_RE] {
        if let Some(cap) = pattern.captures(html) {
            let challenge = cap
                .name("challenge")
                .expect("challenge capture")
                .as_str()
                .trim();
            if !challenge.is_empty() {
                return Ok(challenge.to_owned());
            }
        }
    }
    Err(VirtualPadError(
        "Virtual keyboard carried no matrixRandomChallenge".to_owned(),
    ))
}

/// Translate an ASCII-digit password into the pipe-joined key codes to submit.
pub fn encode_password(password: &str, keys: &[String; 10]) -> Result<String, VirtualPadError> {
    if password.is_empty() {
        return Err(VirtualPadError("Password is empty".to_owned()));
    }
    let mut encoded = Vec::with_capacity(password.len());
    for ch in password.chars() {
        if !ch.is_ascii_digit() {
            return Err(VirtualPadError(
                "BoursoBank passwords are digits only".to_owned(),
            ));
        }
        let digit = (ch as u8 - b'0') as usize;
        encoded.push(keys[digit].as_str());
    }
    Ok(encoded.join("|"))
}
