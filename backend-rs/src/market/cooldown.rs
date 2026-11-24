//! YahooCooldown: the pause a Yahoo 429 buys, shared by every Yahoo call site that yields to it.
//!
//! Asymmetric on purpose: the price path arms it but never reads it (prices must not wait on a
//! pause a logo lookup armed); the dividend pass, the logo path and the crypto probe read it.

use std::sync::Mutex;
use std::time::Duration;

use chrono::{DateTime, Utc};

use crate::clock::AppClock;

use super::num;

#[derive(Debug)]
pub struct YahooCooldown {
    clock: AppClock,
    until: Mutex<DateTime<Utc>>,
}

impl YahooCooldown {
    pub fn new(clock: AppClock) -> Self {
        Self {
            clock,
            until: Mutex::new(DateTime::<Utc>::UNIX_EPOCH),
        }
    }

    /// Arms the pause from a 429's Retry-After header; never shortens a pause already in force.
    /// Returns the pause this 429 asked for.
    pub fn arm(&self, retry_after: Option<&str>) -> Duration {
        let pause = num::retry_after(retry_after);
        let candidate = self.clock.now()
            + chrono::Duration::from_std(pause).unwrap_or(chrono::Duration::zero());
        let mut until = self.until.lock().unwrap_or_else(|e| e.into_inner());
        if candidate > *until {
            *until = candidate;
        }
        pause
    }

    /// Whether a 429 pause is still in force.
    pub fn active(&self) -> bool {
        self.clock.now() < self.until()
    }

    /// When the pause ends (the epoch when none was ever armed).
    pub fn until(&self) -> DateTime<Utc> {
        *self.until.lock().unwrap_or_else(|e| e.into_inner())
    }
}
