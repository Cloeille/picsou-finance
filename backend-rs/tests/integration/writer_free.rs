//! While a provider takes its time to answer, the database stays writable: no flow keeps a
//! transaction open across an outbound call. SQLite has one writer, so a flow that did would
//! make every other write wait, and fail after 30 seconds.

use std::future::Future;
use std::sync::Arc;
use std::time::Duration;

use picsou::db::{self, Db};
use tokio::sync::Notify;
use wiremock::{Request, Respond, ResponseTemplate};

/// How long the slow provider takes, and how long the write made meanwhile may take: it has to
/// finish well before the provider answers.
const ANSWER_DELAY: Duration = Duration::from_millis(1500);
const WRITE_BUDGET: Duration = Duration::from_millis(750);

/// A provider answer that tells when its request arrived, then takes its time.
pub struct Slow {
    asked: Arc<Notify>,
    answer: ResponseTemplate,
}

impl Slow {
    /// The responder to mount, and the signal that it was asked.
    pub fn answering(answer: ResponseTemplate) -> (Self, Arc<Notify>) {
        let asked = Arc::new(Notify::new());
        (
            Self {
                asked: Arc::clone(&asked),
                answer,
            },
            asked,
        )
    }
}

impl Respond for Slow {
    fn respond(&self, _request: &Request) -> ResponseTemplate {
        self.asked.notify_one();
        self.answer.clone().set_delay(ANSWER_DELAY)
    }
}

/// Runs `work` and, as soon as the slow provider has been asked, writes to the database: the
/// write has to go through while the provider is still answering.
pub async fn stays_writable_while<T>(db: &Db, asked: &Notify, work: impl Future<Output = T>) -> T {
    let write_meanwhile = async {
        tokio::time::timeout(Duration::from_secs(10), asked.notified())
            .await
            .expect("the slow provider was never asked");
        tokio::time::timeout(WRITE_BUDGET, write(db))
            .await
            .expect("a write waited for the flow while its provider was answering")
            .expect("the write made meanwhile");
    };
    tokio::join!(work, write_meanwhile).0
}

/// Runs `work` while another transaction holds the writer: `work` must not need it.
pub async fn needs_no_writer<T>(db: &Db, work: impl Future<Output = T>) -> T {
    let mut held = db.begin().await.expect("begin");
    db::query(PROBE)
        .execute(&mut held)
        .await
        .expect("a write that takes the writer");
    let out = tokio::time::timeout(Duration::from_secs(10), work)
        .await
        .expect("the flow waited for the writer another transaction holds");
    held.rollback().await.expect("rollback");
    out
}

const PROBE: &str = "INSERT INTO app_setting (setting_key, value, updated_at) \
     VALUES ('writer.probe', 'ok', NOW()) \
     ON CONFLICT (setting_key) DO UPDATE SET updated_at = EXCLUDED.updated_at";

async fn write(db: &Db) -> sqlx::Result<()> {
    let mut tx = db.begin().await?;
    db::query(PROBE).execute(&mut tx).await?;
    tx.commit().await
}
