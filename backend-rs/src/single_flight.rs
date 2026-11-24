//! One caller at a time, without a lock held across an await.

use std::sync::atomic::{AtomicBool, Ordering};

#[derive(Debug, Default)]
pub struct SingleFlight(AtomicBool);

impl SingleFlight {
    /// The right to run, or None while another caller holds it. Dropping the permit gives it
    /// back, which covers a request future dropped in the middle of its work (the client went
    /// away, a timeout fired): a flag cleared by hand after the await would stay raised.
    pub fn try_enter(&self) -> Option<Permit<'_>> {
        (!self.0.swap(true, Ordering::AcqRel)).then_some(Permit(&self.0))
    }
}

pub struct Permit<'a>(&'a AtomicBool);

impl Drop for Permit<'_> {
    fn drop(&mut self) {
        self.0.store(false, Ordering::Release);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn a_holder_dropped_mid_flight_lets_the_next_caller_in() {
        let flight = SingleFlight::default();
        {
            let work = async {
                let _permit = flight.try_enter().expect("the first caller enters");
                std::future::pending::<()>().await;
            };
            tokio::pin!(work);
            assert!(futures::poll!(work.as_mut()).is_pending());
            assert!(flight.try_enter().is_none(), "one caller at a time");
        }
        assert!(flight.try_enter().is_some());
    }
}
