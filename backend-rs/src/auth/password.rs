//! BCrypt at cost 12, run off the async threads.

use tokio::sync::OnceCell;

use crate::error::AppError;

pub const COST: u32 = 12;

pub async fn hash(plain: &str) -> Result<String, AppError> {
    let plain = plain.to_owned();
    tokio::task::spawn_blocking(move || bcrypt::hash(plain, COST))
        .await
        .map_err(AppError::internal)?
        .map_err(AppError::internal)
}

/// Spring's BCryptPasswordEncoder.matches: false for an empty or malformed hash, without work.
pub async fn matches(plain: &str, hash: &str) -> bool {
    if hash.is_empty() {
        return false;
    }
    let (plain, hash) = (plain.to_owned(), hash.to_owned());
    tokio::task::spawn_blocking(move || bcrypt::verify(plain, &hash).unwrap_or(false))
        .await
        .unwrap_or(false)
}

/// A throwaway hash at the same cost, so an unknown user or a pending activation costs the same
/// bcrypt round as a wrong password (login timing-attack protection).
#[derive(Debug, Default)]
pub struct TimingEqualizer(OnceCell<String>);

impl TimingEqualizer {
    pub async fn hash(&self) -> &str {
        self.0
            .get_or_init(|| async { hash("login-timing-equalizer").await.unwrap_or_default() })
            .await
    }

    /// Runs a full comparison against the dummy hash and discards the result.
    pub async fn burn(&self, plain: &str) {
        let dummy = self.hash().await.to_owned();
        let _ = matches(plain, &dummy).await;
    }
}
