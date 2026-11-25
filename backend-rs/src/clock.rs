//! The injectable clock: every "now" and "today" in the app comes from here, so tests fix it.

use std::sync::{Arc, Mutex};
use std::time::Duration;

use chrono::{DateTime, NaiveDate, NaiveDateTime, Utc};
use chrono_tz::Tz;

pub trait Clock: Send + Sync + std::fmt::Debug {
    fn now(&self) -> DateTime<Utc>;
}

#[derive(Debug, Default)]
pub struct SystemClock;

impl Clock for SystemClock {
    fn now(&self) -> DateTime<Utc> {
        Utc::now()
    }
}

/// A clock that only moves when told to.
#[derive(Debug)]
pub struct FixedClock(Mutex<DateTime<Utc>>);

impl FixedClock {
    pub fn new(at: DateTime<Utc>) -> Self {
        Self(Mutex::new(at))
    }

    pub fn set(&self, at: DateTime<Utc>) {
        *self.0.lock().unwrap_or_else(|e| e.into_inner()) = at;
    }

    pub fn advance(&self, by: Duration) {
        let mut now = self.0.lock().unwrap_or_else(|e| e.into_inner());
        *now += chrono::Duration::from_std(by).unwrap_or(chrono::Duration::MAX);
    }
}

impl Clock for FixedClock {
    fn now(&self) -> DateTime<Utc> {
        *self.0.lock().unwrap_or_else(|e| e.into_inner())
    }
}

/// The clock plus the application's civil time zone (APP_TIMEZONE).
#[derive(Debug, Clone)]
pub struct AppClock {
    clock: Arc<dyn Clock>,
    zone: Tz,
}

impl AppClock {
    pub fn new(clock: Arc<dyn Clock>, zone: Tz) -> Self {
        Self { clock, zone }
    }

    pub fn now(&self) -> DateTime<Utc> {
        self.clock.now()
    }

    pub fn zone(&self) -> Tz {
        self.zone
    }

    /// LocalDate.now() in the application zone.
    pub fn today(&self) -> NaiveDate {
        self.now().with_timezone(&self.zone).date_naive()
    }

    /// LocalDateTime.now() in the application zone.
    pub fn local_now(&self) -> NaiveDateTime {
        self.now().with_timezone(&self.zone).naive_local()
    }
}
