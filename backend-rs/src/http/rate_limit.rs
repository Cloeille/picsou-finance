//! Named token buckets equivalent to the Java RateLimitConfig (Bucket4j, intervally refilled),
//! keyed by client IP, member id or key id, in a bounded store that forgets idle keys.

use std::collections::HashMap;
use std::sync::Mutex;
use std::time::Duration;

use chrono::{DateTime, Utc};

use crate::clock::AppClock;

/// One limit: capacity tokens, all refilled at once every period.
#[derive(Debug, Clone, Copy)]
pub struct Limit {
    pub capacity: u64,
    pub period: Duration,
}

/// A named bucket policy; every limit must allow the request.
#[derive(Debug)]
pub struct Policy {
    pub name: &'static str,
    pub limits: &'static [Limit],
    /// Store eviction: idle keys are forgotten after this long without access, or after this
    /// long since creation when expire_after_write is set.
    pub expire_after: Duration,
    pub expire_after_write: bool,
}

const fn per_minutes(capacity: u64, minutes: u64) -> Limit {
    Limit {
        capacity,
        period: Duration::from_secs(minutes * 60),
    }
}

const HOUR: Duration = Duration::from_secs(3600);

macro_rules! policies {
    ($($id:ident = $name:literal: [$($cap:literal / $min:literal),+];)*) => {
        $(pub const $id: Policy = Policy {
            name: $name,
            limits: &[$(per_minutes($cap, $min)),+],
            expire_after: HOUR,
            expire_after_write: false,
        };)*
    };
}

/// Every bucket of RateLimitConfig, with its budget in tokens per minutes.
pub mod policies {
    use super::*;

    policies! {
        LOGIN = "loginBuckets": [5 / 15];
        SSO_START = "ssoStartBuckets": [30 / 15];
        REAUTH = "reauthBuckets": [5 / 15];
        SYNC = "syncBuckets": [10 / 1];
        TELEMETRY = "telemetryBuckets": [30 / 1];
        TR_AUTH = "trAuthBuckets": [3 / 10];
        BOURSO_AUTH = "boursoAuthBuckets": [5 / 15];
        CAISSE_EPARGNE_AUTH = "caisseEpargneAuthBuckets": [3 / 15];
        CAISSE_EPARGNE_KEYPAD = "caisseEpargneKeypadBuckets": [5 / 15];
        LBP_AUTH = "lbpAuthBuckets": [3 / 15];
        LBP_KEYPAD = "lbpKeypadBuckets": [5 / 15];
        BNP_AUTH = "bnpAuthBuckets": [3 / 15];
        BNP_KEYPAD = "bnpKeypadBuckets": [5 / 15];
        BNP_COMPLETE = "bnpCompleteBuckets": [10 / 15];
        BNP_WEBVIEW = "bnpWebviewBuckets": [10 / 15];
        BANQUE_POPULAIRE_AUTH = "banquePopulaireAuthBuckets": [3 / 15];
        BANQUE_POPULAIRE_KEYPAD = "banquePopulaireKeypadBuckets": [5 / 15];
        REVOLUT_AUTH = "revolutAuthBuckets": [5 / 15];
        BOURSE_DIRECT_AUTH = "bourseDirectAuthBuckets": [5 / 15];
        DEGIRO_AUTH = "degiroAuthBuckets": [5 / 15];
        AMUNDI_AUTH = "amundiAuthBuckets": [5 / 15];
        CIC_AUTH = "cicAuthBuckets": [5 / 15];
        CIC_COMPLETE = "cicCompleteBuckets": [10 / 15];
        CMES_AUTH = "cmesAuthBuckets": [5 / 15];
        CMES_COMPLETE = "cmesCompleteBuckets": [10 / 15];
        NATIXIS_AUTH = "natixisAuthBuckets": [5 / 15];
        NATIXIS_COMPLETE = "natixisCompleteBuckets": [10 / 15];
        CORUM_AUTH = "corumAuthBuckets": [5 / 15];
        CORUM_RELOGIN = "corumReloginBuckets": [5 / 15];
        LOUVE_AUTH = "louveAuthBuckets": [10 / 15];
        SOFIDY_AUTH = "sofidyAuthBuckets": [5 / 15];
        GEOCODE = "geocodeBuckets": [60 / 1];
        FORTUNEO_AUTH = "fortuneoAuthBuckets": [5 / 15];
        AMEX_AUTH = "amexAuthBuckets": [5 / 15];
        IBKR_SYNC = "ibkrSyncBuckets": [6 / 1];
        SIMPLEFIN_REQUEST = "simplefinRequestBuckets": [6 / 1];
        WISE_REQUEST = "wiseRequestBuckets": [6 / 1];
        SETUP = "setupBuckets": [10 / 1];
        MFA_VERIFY = "mfaVerifyBuckets": [5 / 15];
        MFA_ENROLL = "mfaEnrollBuckets": [10 / 60];
        EXPORT = "exportBuckets": [5 / 60];
        ACCOUNT_EXPORT = "accountExportBuckets": [20 / 60];
        LOGO = "logoBuckets": [60 / 1];
        MCP_KEY = "mcpKeyBuckets": [120 / 1];
        ACCESS_KEY_CREATE = "accessKeyCreateBuckets": [10 / 60];
        OAUTH_REGISTER = "oauthRegisterBuckets": [10 / 15];
    }

    /// One sync per 15 minutes and four per day, per member; kept 24 h after creation.
    pub const MCP_MEMBER_SYNC: Policy = Policy {
        name: "mcpMemberSyncBuckets",
        limits: &[per_minutes(1, 15), per_minutes(4, 24 * 60)],
        expire_after: Duration::from_secs(24 * 3600),
        expire_after_write: true,
    };
}

const MAX_KEYS_PER_POLICY: usize = 50_000;

#[derive(Debug, Clone)]
struct BucketState {
    tokens: Vec<u64>,
    last_refill: Vec<DateTime<Utc>>,
    created: DateTime<Utc>,
    accessed: DateTime<Utc>,
}

impl BucketState {
    fn new(policy: &Policy, now: DateTime<Utc>) -> Self {
        Self {
            tokens: policy.limits.iter().map(|l| l.capacity).collect(),
            last_refill: vec![now; policy.limits.len()],
            created: now,
            accessed: now,
        }
    }

    fn refill(&mut self, policy: &Policy, now: DateTime<Utc>) {
        for (i, limit) in policy.limits.iter().enumerate() {
            let period = chrono::Duration::from_std(limit.period).unwrap_or(chrono::Duration::MAX);
            let elapsed = now - self.last_refill[i];
            if elapsed >= period {
                let periods =
                    (elapsed.num_milliseconds() / period.num_milliseconds().max(1)).max(1);
                let added = limit
                    .capacity
                    .saturating_mul(u64::try_from(periods).unwrap_or(u64::MAX));
                self.tokens[i] = self.tokens[i].saturating_add(added).min(limit.capacity);
                self.last_refill[i] += period * i32::try_from(periods).unwrap_or(i32::MAX);
            }
        }
    }

    /// The wait until every limit has a token again.
    fn wait(&self, policy: &Policy, now: DateTime<Utc>) -> Duration {
        policy
            .limits
            .iter()
            .enumerate()
            .filter(|(i, _)| self.tokens[*i] == 0)
            .map(|(i, l)| {
                let next = self.last_refill[i]
                    + chrono::Duration::from_std(l.period).unwrap_or(chrono::Duration::MAX);
                (next - now).to_std().unwrap_or_default()
            })
            .max()
            .unwrap_or_default()
    }
}

/// The outcome of a consumption that may need a Retry-After.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Probe {
    Consumed,
    Refused { retry_after: Duration },
}

impl Probe {
    /// Whole seconds, at least one, as PerIpRateLimit writes Retry-After.
    pub fn retry_after_seconds(&self) -> u64 {
        match self {
            Self::Consumed => 0,
            Self::Refused { retry_after } => {
                let ms = u64::try_from(retry_after.as_millis()).unwrap_or(u64::MAX);
                ms.div_ceil(1000).max(1)
            }
        }
    }
}

#[derive(Debug)]
pub struct RateLimiter {
    clock: AppClock,
    stores: Mutex<HashMap<&'static str, HashMap<String, BucketState>>>,
}

impl RateLimiter {
    pub fn new(clock: AppClock) -> Self {
        Self {
            clock,
            stores: Mutex::new(HashMap::new()),
        }
    }

    /// Takes one token from the key's bucket, creating it on first use.
    pub fn try_consume(&self, policy: &Policy, key: &str) -> bool {
        self.probe(policy, key) == Probe::Consumed
    }

    pub fn probe(&self, policy: &Policy, key: &str) -> Probe {
        let now = self.clock.now();
        let mut stores = self.stores.lock().unwrap_or_else(|e| e.into_inner());
        let store = stores.entry(policy.name).or_default();
        let expire =
            chrono::Duration::from_std(policy.expire_after).unwrap_or(chrono::Duration::MAX);
        if let Some(state) = store.get(key) {
            let anchor = if policy.expire_after_write {
                state.created
            } else {
                state.accessed
            };
            if now - anchor >= expire {
                store.remove(key);
            }
        }
        if !store.contains_key(key) && store.len() >= MAX_KEYS_PER_POLICY {
            store.retain(|_, s| {
                now - if policy.expire_after_write {
                    s.created
                } else {
                    s.accessed
                } < expire
            });
            if store.len() >= MAX_KEYS_PER_POLICY
                && let Some(oldest) = store
                    .iter()
                    .min_by_key(|(_, s)| s.accessed)
                    .map(|(k, _)| k.clone())
            {
                store.remove(&oldest);
            }
        }
        let state = store
            .entry(key.to_owned())
            .or_insert_with(|| BucketState::new(policy, now));
        state.accessed = now;
        state.refill(policy, now);
        if state.tokens.iter().all(|t| *t > 0) {
            state.tokens.iter_mut().for_each(|t| *t -= 1);
            Probe::Consumed
        } else {
            Probe::Refused {
                retry_after: state.wait(policy, now),
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use std::sync::Arc;

    use super::*;
    use crate::clock::FixedClock;

    #[test]
    fn refills_the_whole_budget_each_period() {
        let fixed = Arc::new(FixedClock::new(Utc::now()));
        let limiter = RateLimiter::new(AppClock::new(fixed.clone(), chrono_tz::UTC));
        for _ in 0..5 {
            assert!(limiter.try_consume(&policies::LOGIN, "1.2.3.4"));
        }
        assert!(!limiter.try_consume(&policies::LOGIN, "1.2.3.4"));
        assert!(limiter.try_consume(&policies::LOGIN, "5.6.7.8"));
        fixed.advance(Duration::from_secs(14 * 60));
        assert_eq!(
            limiter
                .probe(&policies::LOGIN, "1.2.3.4")
                .retry_after_seconds(),
            60
        );
        fixed.advance(Duration::from_secs(60));
        assert!(limiter.try_consume(&policies::LOGIN, "1.2.3.4"));
    }
}
