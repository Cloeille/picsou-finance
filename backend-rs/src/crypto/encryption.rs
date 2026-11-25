//! AES-GCM byte-compatible with the Java CryptoEncryption: base64(iv(12) || ciphertext || tag(16)).

use aes_gcm::aead::{Aead, KeyInit};
use aes_gcm::{Aes128Gcm, Aes256Gcm, Nonce};
use base64::Engine;
use base64::engine::general_purpose::STANDARD;
use rand::RngExt;

const IV_LENGTH: usize = 12;

#[derive(Debug, thiserror::Error)]
pub enum CryptoError {
    #[error("{0}")]
    InvalidKey(String),
    #[error("Encryption failed")]
    Encrypt,
    #[error("Decryption failed")]
    Decrypt,
}

/// AES-192 is accepted by the Java class; the aes-gcm crate has no 192-bit alias, so it is built
/// from the generic type.
type Aes192Gcm = aes_gcm::AesGcm<aes::Aes192, aes_gcm::aead::consts::U12>;

#[derive(Clone)]
enum Cipher {
    Aes128(Box<Aes128Gcm>),
    Aes192(Box<Aes192Gcm>),
    Aes256(Box<Aes256Gcm>),
}

#[derive(Clone)]
pub struct CryptoEncryption {
    cipher: Cipher,
}

impl std::fmt::Debug for CryptoEncryption {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str("CryptoEncryption")
    }
}

impl CryptoEncryption {
    pub fn new(base64_key: &str) -> Result<Self, CryptoError> {
        if base64_key.trim().is_empty() {
            return Err(CryptoError::InvalidKey(
                "CRYPTO_ENCRYPTION_KEY is required. Generate one with: openssl rand -base64 32"
                    .into(),
            ));
        }
        let key = STANDARD.decode(base64_key).map_err(|_| {
            CryptoError::InvalidKey(
                "CRYPTO_ENCRYPTION_KEY is not valid base64. Generate one with: openssl rand -base64 32".into(),
            )
        })?;
        let cipher = match key.len() {
            16 => Cipher::Aes128(Box::new(
                Aes128Gcm::new_from_slice(&key).map_err(|_| CryptoError::Encrypt)?,
            )),
            24 => Cipher::Aes192(Box::new(
                Aes192Gcm::new_from_slice(&key).map_err(|_| CryptoError::Encrypt)?,
            )),
            32 => Cipher::Aes256(Box::new(
                Aes256Gcm::new_from_slice(&key).map_err(|_| CryptoError::Encrypt)?,
            )),
            n => {
                return Err(CryptoError::InvalidKey(format!(
                    "CRYPTO_ENCRYPTION_KEY must decode to 16, 24 or 32 bytes (AES-128/192/256); got {n} bytes. \
                     Generate a 256-bit key with: openssl rand -base64 32"
                )));
            }
        };
        Ok(Self { cipher })
    }

    pub fn encrypt(&self, plain: &str) -> Result<String, CryptoError> {
        let iv: [u8; IV_LENGTH] = rand::rng().random();
        let nonce = Nonce::from(iv);
        let nonce = &nonce;
        let sealed = match &self.cipher {
            Cipher::Aes128(c) => c.encrypt(nonce, plain.as_bytes()),
            Cipher::Aes192(c) => c.encrypt(nonce, plain.as_bytes()),
            Cipher::Aes256(c) => c.encrypt(nonce, plain.as_bytes()),
        }
        .map_err(|_| CryptoError::Encrypt)?;
        let mut combined = Vec::with_capacity(IV_LENGTH + sealed.len());
        combined.extend_from_slice(&iv);
        combined.extend_from_slice(&sealed);
        Ok(STANDARD.encode(combined))
    }

    pub fn decrypt(&self, cipher_text: &str) -> Result<String, CryptoError> {
        let combined = STANDARD
            .decode(cipher_text)
            .map_err(|_| CryptoError::Decrypt)?;
        if combined.len() < IV_LENGTH {
            return Err(CryptoError::Decrypt);
        }
        let (iv, sealed) = combined.split_at(IV_LENGTH);
        let nonce = Nonce::try_from(iv).map_err(|_| CryptoError::Decrypt)?;
        let nonce = &nonce;
        let plain = match &self.cipher {
            Cipher::Aes128(c) => c.decrypt(nonce, sealed),
            Cipher::Aes192(c) => c.decrypt(nonce, sealed),
            Cipher::Aes256(c) => c.decrypt(nonce, sealed),
        }
        .map_err(|_| CryptoError::Decrypt)?;
        String::from_utf8(plain).map_err(|_| CryptoError::Decrypt)
    }
}
