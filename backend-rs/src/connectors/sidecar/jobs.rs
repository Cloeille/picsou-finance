//! The sync executors: one bounded queue per bank (SyncExecutorConfig), and a count of the jobs
//! still running so a caller (a test, a shutdown) can wait for them.

use std::collections::HashMap;
use std::future::Future;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};

use tokio::sync::{Notify, Semaphore};

/// A pool's shape: concurrent workers and how many jobs may wait for one.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Pool {
    pub workers: usize,
    pub queue: usize,
}

impl Pool {
    /// singleWorker: one worker, a hundred queued; a bank's syncs never overlap.
    pub const SINGLE_WORKER: Pool = Pool {
        workers: 1,
        queue: 100,
    };
}

struct Executor {
    permits: Arc<Semaphore>,
    accepted: AtomicUsize,
    pool: Pool,
}

/// The executor refused the job (RejectedExecutionException): its queue is full.
#[derive(Debug, Clone, Copy, PartialEq, Eq, thiserror::Error)]
#[error("the {0} sync executor refused the job")]
pub struct Refused(pub &'static str);

#[derive(Default)]
pub struct Jobs {
    executors: Mutex<HashMap<&'static str, Arc<Executor>>>,
    running: Arc<AtomicUsize>,
    idle: Arc<Notify>,
}

struct Running {
    executor: Arc<Executor>,
    running: Arc<AtomicUsize>,
    idle: Arc<Notify>,
}

impl Drop for Running {
    fn drop(&mut self) {
        self.executor.accepted.fetch_sub(1, Ordering::SeqCst);
        if self.running.fetch_sub(1, Ordering::SeqCst) == 1 {
            self.idle.notify_waiters();
        }
    }
}

impl Jobs {
    /// Runs `job` on the named executor, created with `pool` on first use.
    pub fn submit(
        &self,
        name: &'static str,
        pool: Pool,
        job: impl Future<Output = ()> + Send + 'static,
    ) -> Result<(), Refused> {
        let executor = self
            .executors
            .lock()
            .expect("executor registry")
            .entry(name)
            .or_insert_with(|| {
                Arc::new(Executor {
                    permits: Arc::new(Semaphore::new(pool.workers)),
                    accepted: AtomicUsize::new(0),
                    pool,
                })
            })
            .clone();
        let capacity = executor.pool.workers + executor.pool.queue;
        if executor.accepted.fetch_add(1, Ordering::SeqCst) >= capacity {
            executor.accepted.fetch_sub(1, Ordering::SeqCst);
            return Err(Refused(name));
        }
        self.running.fetch_add(1, Ordering::SeqCst);
        let guard = Running {
            executor: executor.clone(),
            running: self.running.clone(),
            idle: self.idle.clone(),
        };
        tokio::spawn(async move {
            let _guard = guard;
            let Ok(_permit) = executor.permits.clone().acquire_owned().await else {
                return;
            };
            job.await;
        });
        Ok(())
    }

    /// Resolves once no submitted job is queued or running.
    pub async fn wait_idle(&self) {
        loop {
            let notified = self.idle.notified();
            if self.running.load(Ordering::SeqCst) == 0 {
                return;
            }
            notified.await;
        }
    }
}
