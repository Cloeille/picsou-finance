//! BitcoinKeyUtils: BIP32 public derivation, Base58Check, Bech32 and the parsing of the extended
//! keys and output descriptors a user can paste. Every derived address is P2WPKH (bc1q...).

use hmac::{Hmac, KeyInit, Mac};
use k256::elliptic_curve::PrimeField;
use k256::elliptic_curve::group::Group;
use k256::elliptic_curve::sec1::{FromSec1Point, ToSec1Point};
use k256::{AffinePoint, FieldBytes, ProjectivePoint, Scalar, Sec1Point};
use ripemd::Ripemd160;
use sha2::{Digest, Sha256, Sha512};

const XPUB_VERSION: [u8; 4] = [0x04, 0x88, 0xb2, 0x1e];

/// BIP44 gap limit: scanning a chain stops after this many consecutive unused addresses.
pub const GAP_LIMIT: u32 = 20;

const BASE58_CHARS: &[u8; 58] = b"123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
const BECH32_CHARSET: &[u8; 32] = b"qpzry9x8gf2tvdw0s3jn54khce6mua7l";

/// A parsed extended public key: chain code and compressed public key (33 bytes).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Xpub {
    pub chain_code: [u8; 32],
    pub pub_key: [u8; 33],
}

/// Whether the input is an extended public key or an output descriptor, not a plain address.
pub fn is_extended_key(input: &str) -> bool {
    let t = input.trim();
    ["xpub", "zpub", "ypub", "wpkh(", "pkh("]
        .iter()
        .any(|prefix| t.starts_with(prefix))
}

/// Any extended key or descriptor as a standard xpub string: zpub and ypub get the xpub version
/// bytes, a descriptor gives up the key inside it.
pub fn normalize_to_xpub(input: &str) -> Result<String, String> {
    let t = input.trim();
    if t.starts_with("wpkh(") || t.starts_with("pkh(") {
        return extract_xpub_from_descriptor(t);
    }
    if t.starts_with("zpub") || t.starts_with("ypub") {
        return swap_version_to_xpub(t);
    }
    Ok(t.to_owned())
}

fn extract_xpub_from_descriptor(descriptor: &str) -> Result<String, String> {
    let start = ["xpub", "zpub", "ypub"]
        .iter()
        .filter_map(|prefix| descriptor.find(prefix))
        .min()
        .ok_or("No extended public key found in descriptor")?;
    let key: String = descriptor[start..]
        .chars()
        .take_while(|c| c.is_ascii() && BASE58_CHARS.contains(&(*c as u8)))
        .collect();
    if key.starts_with("zpub") || key.starts_with("ypub") {
        return swap_version_to_xpub(&key);
    }
    Ok(key)
}

fn swap_version_to_xpub(key: &str) -> Result<String, String> {
    let mut decoded = base58_decode_checked(key)?;
    if decoded.len() < XPUB_VERSION.len() {
        return Err("Extended key too short".into());
    }
    decoded[..4].copy_from_slice(&XPUB_VERSION);
    Ok(base58_encode_checked(&decoded))
}

/// BIP32 serialization: version(4) depth(1) fingerprint(4) child(4) chain code(32) key(33).
pub fn parse_xpub(xpub: &str) -> Result<Xpub, String> {
    let data = base58_decode_checked(xpub)?;
    if data.len() != 78 {
        return Err(format!("Invalid xpub length: {}", data.len()));
    }
    let mut chain_code = [0u8; 32];
    chain_code.copy_from_slice(&data[13..45]);
    let mut pub_key = [0u8; 33];
    pub_key.copy_from_slice(&data[45..78]);
    Ok(Xpub {
        chain_code,
        pub_key,
    })
}

/// CKDpub((K, c), i) for a non-hardened index: HMAC-SHA512(c, K || i), then IL*G + K.
pub fn derive_child(parent: &Xpub, index: u32) -> Result<Xpub, String> {
    if index >= 0x8000_0000 {
        return Err("Hardened derivation not supported for public keys".into());
    }
    let mut mac = Hmac::<Sha512>::new_from_slice(&parent.chain_code)
        .map_err(|_| "BIP32 child key derivation failed".to_owned())?;
    mac.update(&parent.pub_key);
    mac.update(&index.to_be_bytes());
    let i = mac.finalize().into_bytes();
    let (il, ir) = i.split_at(32);

    let il = Option::<Scalar>::from(Scalar::from_repr(
        FieldBytes::try_from(il).map_err(|_| "BIP32 child key derivation failed".to_owned())?,
    ))
    .ok_or("Derived key is invalid (IL >= N)")?;
    let parent_point = decode_point(&parent.pub_key)?;
    let child = ProjectivePoint::GENERATOR * il + parent_point;
    if bool::from(child.is_identity()) {
        return Err("Derived key is the point at infinity".into());
    }
    let encoded = child.to_affine().to_sec1_point(true);
    let mut pub_key = [0u8; 33];
    pub_key.copy_from_slice(encoded.as_bytes());
    let mut chain_code = [0u8; 32];
    chain_code.copy_from_slice(ir);
    Ok(Xpub {
        chain_code,
        pub_key,
    })
}

fn decode_point(bytes: &[u8]) -> Result<ProjectivePoint, String> {
    let encoded = Sec1Point::from_bytes(bytes).map_err(|_| "Invalid public key encoding")?;
    Option::<AffinePoint>::from(AffinePoint::from_sec1_point(&encoded))
        .map(ProjectivePoint::from)
        .ok_or_else(|| "Public key is not on the curve".to_owned())
}

/// The native SegWit address (witness version 0) of a compressed public key.
pub fn to_p2wpkh_address(compressed_pub_key: &[u8]) -> String {
    let sha = Sha256::digest(compressed_pub_key);
    let hash160 = Ripemd160::digest(sha);
    bech32_encode("bc", 0, &hash160)
}

pub fn base58_decode_checked(input: &str) -> Result<Vec<u8>, String> {
    let decoded = base58_decode(input)?;
    if decoded.len() < 4 {
        return Err("Input too short for Base58Check".into());
    }
    let (payload, checksum) = decoded.split_at(decoded.len() - 4);
    if checksum != &sha256d(payload)[..4] {
        return Err("Invalid Base58Check checksum".into());
    }
    Ok(payload.to_vec())
}

pub fn base58_encode_checked(payload: &[u8]) -> String {
    let mut full = payload.to_vec();
    full.extend_from_slice(&sha256d(payload)[..4]);
    base58_encode(&full)
}

fn base58_decode(input: &str) -> Result<Vec<u8>, String> {
    // Big-endian magnitude, built digit by digit (value = value * 58 + digit).
    let mut magnitude: Vec<u8> = Vec::new();
    for c in input.chars() {
        let digit = (c.is_ascii())
            .then(|| BASE58_CHARS.iter().position(|b| *b == c as u8))
            .flatten()
            .ok_or_else(|| format!("Invalid Base58 character: {c}"))?;
        let mut carry = digit as u32;
        for byte in magnitude.iter_mut().rev() {
            let value = u32::from(*byte) * 58 + carry;
            *byte = (value & 0xff) as u8;
            carry = value >> 8;
        }
        while carry > 0 {
            magnitude.insert(0, (carry & 0xff) as u8);
            carry >>= 8;
        }
    }
    let leading_zeros = input.chars().take_while(|c| *c == '1').count();
    let mut bytes = magnitude;
    if bytes.is_empty() {
        // BigInteger.ZERO.toByteArray() is one zero byte, which the Java decoder keeps.
        bytes.push(0);
    }
    let mut result = vec![0u8; leading_zeros];
    result.extend_from_slice(&bytes);
    Ok(result)
}

fn base58_encode(input: &[u8]) -> String {
    let mut digits: Vec<u8> = Vec::new();
    for byte in input {
        let mut carry = u32::from(*byte);
        for digit in digits.iter_mut() {
            let value = u32::from(*digit) * 256 + carry;
            *digit = (value % 58) as u8;
            carry = value / 58;
        }
        while carry > 0 {
            digits.push((carry % 58) as u8);
            carry /= 58;
        }
    }
    let leading = input.iter().take_while(|b| **b == 0).count();
    let mut out = String::with_capacity(leading + digits.len());
    out.extend(std::iter::repeat_n('1', leading));
    out.extend(
        digits
            .iter()
            .rev()
            .map(|d| BASE58_CHARS[*d as usize] as char),
    );
    out
}

fn sha256d(data: &[u8]) -> [u8; 32] {
    Sha256::digest(Sha256::digest(data)).into()
}

fn bech32_encode(hrp: &str, witness_version: u8, program: &[u8]) -> String {
    let mut payload = vec![witness_version];
    payload.extend(convert_bits(program, 8, 5));
    let checksum = bech32_checksum(hrp, &payload);
    let mut out = String::from(hrp);
    out.push('1');
    out.extend(
        payload
            .iter()
            .chain(checksum.iter())
            .map(|b| BECH32_CHARSET[(*b & 0x1f) as usize] as char),
    );
    out
}

/// Regroups bits, padding the last group with zeros.
fn convert_bits(data: &[u8], from_bits: u32, to_bits: u32) -> Vec<u8> {
    let mut acc: u32 = 0;
    let mut bits: u32 = 0;
    let maxv = (1u32 << to_bits) - 1;
    let mut out = Vec::new();
    for b in data {
        acc = (acc << from_bits) | u32::from(*b);
        bits += from_bits;
        while bits >= to_bits {
            bits -= to_bits;
            out.push(((acc >> bits) & maxv) as u8);
        }
    }
    if bits > 0 {
        out.push(((acc << (to_bits - bits)) & maxv) as u8);
    }
    out
}

fn bech32_checksum(hrp: &str, data: &[u8]) -> [u8; 6] {
    let mut values: Vec<u8> = hrp.bytes().map(|c| c >> 5).collect();
    values.push(0);
    values.extend(hrp.bytes().map(|c| c & 0x1f));
    values.extend_from_slice(data);
    values.extend_from_slice(&[0; 6]);
    let polymod = bech32_polymod(&values) ^ 1;
    let mut checksum = [0u8; 6];
    for (i, slot) in checksum.iter_mut().enumerate() {
        *slot = ((polymod >> (5 * (5 - i))) & 0x1f) as u8;
    }
    checksum
}

fn bech32_polymod(values: &[u8]) -> u32 {
    const GEN: [u32; 5] = [
        0x3b6a_57b2,
        0x2650_8e6d,
        0x1ea1_19fa,
        0x3d42_33dd,
        0x2a14_62b3,
    ];
    let mut chk: u32 = 1;
    for v in values {
        let top = chk >> 25;
        chk = ((chk & 0x01ff_ffff) << 5) ^ u32::from(*v);
        for (i, g) in GEN.iter().enumerate() {
            if (top >> i) & 1 == 1 {
                chk ^= g;
            }
        }
    }
    chk
}

#[cfg(test)]
mod tests {
    use super::*;

    // BIP32 test vector 2 (public derivation of m/0) and BIP84's reference wallet: published
    // test vectors, nobody's funds.
    const BIP32_V2_MASTER: &str = "xpub661MyMwAqRbcFW31YEwpkMuc5THy2PSt5bDMsktWQcFF8syAmRUapSCGu8ED9W6oDMSgv6Zz8idoc4a6mr8BDzTJY47LJhkJ8UB7WEGuduB";
    const BIP32_V2_M0: &str = "xpub69H7F5d8KSRgmmdJg2KhpAK8SR3DjMwAdkxj3ZuxV27CprR9LgpeyGmXUbC6wb7ERfvrnKZjXoUmmDznezpbZb7ap6r1D3tgFxHmwMkQTPH";
    const BIP84_ZPUB: &str = "zpub6rFR7y4Q2AijBEqTUquhVz398htDFrtymD9xYYfG1m4wAcvPhXNfE3EfH1r1ADqtfSdVCToUG868RvUUkgDKf31mGDtKsAYz2oz2AGutZYs";

    fn address(root: &Xpub, chain: u32, index: u32) -> String {
        let chain_key = derive_child(root, chain).unwrap();
        to_p2wpkh_address(&derive_child(&chain_key, index).unwrap().pub_key)
    }

    #[test]
    fn public_derivation_matches_bip32_vector_2() {
        let master = parse_xpub(BIP32_V2_MASTER).unwrap();
        let child = derive_child(&master, 0).unwrap();
        assert_eq!(child, parse_xpub(BIP32_V2_M0).unwrap());
    }

    #[test]
    fn zpub_derives_the_bip84_reference_addresses() {
        let root = parse_xpub(&normalize_to_xpub(BIP84_ZPUB).unwrap()).unwrap();
        assert_eq!(
            address(&root, 0, 0),
            "bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu"
        );
        assert_eq!(
            address(&root, 0, 1),
            "bc1qnjg0jd8228aq7egyzacy8cys3knf9xvrerkf9g"
        );
        assert_eq!(
            address(&root, 1, 0),
            "bc1q8c6fshw2dlwun7ekn9qwf37cu2rn755upcp6el"
        );
    }

    #[test]
    fn descriptor_gives_up_its_key() {
        let descriptor = format!("wpkh([73c5da0a/84h/0h/0h]{BIP84_ZPUB}/0/*)#abcdefgh");
        assert_eq!(
            normalize_to_xpub(&descriptor).unwrap(),
            normalize_to_xpub(BIP84_ZPUB).unwrap()
        );
        assert!(normalize_to_xpub("wpkh([73c5da0a/84h/0h/0h]nokey/0/*)").is_err());
    }

    #[test]
    fn version_swap_keeps_the_key_material() {
        let xpub = normalize_to_xpub(BIP84_ZPUB).unwrap();
        assert!(xpub.starts_with("xpub"));
        assert_eq!(
            base58_decode_checked(&xpub).unwrap()[4..],
            base58_decode_checked(BIP84_ZPUB).unwrap()[4..]
        );
    }

    #[test]
    fn base58check_rejects_a_bad_checksum_and_bad_characters() {
        let mut broken = BIP32_V2_MASTER.to_owned();
        broken.pop();
        broken.push('C');
        assert_eq!(
            base58_decode_checked(&broken).unwrap_err(),
            "Invalid Base58Check checksum"
        );
        assert!(
            base58_decode_checked("xpub-not-a-key")
                .unwrap_err()
                .starts_with("Invalid Base58 character")
        );
        assert_eq!(
            base58_decode_checked("").unwrap_err(),
            "Input too short for Base58Check"
        );
    }

    #[test]
    fn base58_round_trips_leading_zero_bytes() {
        let payload = [0u8, 0, 1, 2, 255];
        assert_eq!(
            base58_decode_checked(&base58_encode_checked(&payload)).unwrap(),
            payload
        );
    }

    #[test]
    fn hardened_indexes_are_refused() {
        let master = parse_xpub(BIP32_V2_MASTER).unwrap();
        assert!(derive_child(&master, 0x8000_0000).is_err());
    }

    #[test]
    fn detects_extended_keys_and_descriptors() {
        assert!(is_extended_key("  zpub6r"));
        assert!(is_extended_key("pkh([x]xpub)"));
        assert!(!is_extended_key(
            "bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu"
        ));
    }
}
