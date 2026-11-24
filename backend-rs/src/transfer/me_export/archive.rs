//! DataExportService: data.json, then one CSV per exporter, then README.txt, streamed to the
//! client as the ZIP grows. A failure past the first byte cannot change the status, so the
//! archive gets an __EXPORT_FAILED__.txt entry instead.

use std::io::{self, Write};
use std::sync::{Arc, Mutex};

use crate::db::Db;
use bytes::Bytes;
use chrono::{DateTime, Datelike, Local, Timelike, Utc};
use futures::Stream;
use tokio::sync::mpsc;
use zip::write::{SimpleFileOptions, StreamWriter};
use zip::{CompressionMethod, ZipWriter};

use super::Exporting;
use super::exporters::{self, EXPORTERS, Exporter, GOAL_CHILDREN, GOAL_COLUMNS};
use super::json::{Cell, JsonWriter, java_instant};
use crate::db::{AsDbHandle, DbHandle};

#[derive(Debug, thiserror::Error)]
enum ExportError {
    #[error("database: {0}")]
    Db(#[from] sqlx::Error),
    #[error("write: {0}")]
    Io(#[from] io::Error),
    #[error("zip: {0}")]
    Zip(#[from] zip::result::ZipError),
    #[error("client went away")]
    Gone,
}

/// The bytes the ZipWriter produced since the last flush.
#[derive(Clone, Default)]
struct Pending(Arc<Mutex<Vec<u8>>>);

impl Write for Pending {
    fn write(&mut self, buf: &[u8]) -> io::Result<usize> {
        self.0
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .extend_from_slice(buf);
        Ok(buf.len())
    }

    fn flush(&mut self) -> io::Result<()> {
        Ok(())
    }
}

struct Archive {
    zip: ZipWriter<StreamWriter<Pending>>,
    pending: Pending,
    options: SimpleFileOptions,
    sender: mpsc::Sender<Bytes>,
}

impl Archive {
    fn start(&mut self, name: &str) -> Result<(), ExportError> {
        self.zip.start_file(name, self.options)?;
        Ok(())
    }

    /// Hands what the ZIP holds so far to the response; the bounded channel is the
    /// back-pressure that keeps a slow download from buffering the whole export.
    async fn flush(&mut self) -> Result<(), ExportError> {
        let chunk = std::mem::take(&mut *self.pending.0.lock().unwrap_or_else(|e| e.into_inner()));
        if chunk.is_empty() {
            return Ok(());
        }
        self.sender
            .send(Bytes::from(chunk))
            .await
            .map_err(|_| ExportError::Gone)
    }
}

/// Starts the export and returns the body stream it feeds.
pub(super) fn spawn(
    db: Db,
    who: Exporting,
    include_balance_snapshots: bool,
    exported_at: DateTime<Utc>,
) -> impl Stream<Item = Result<Bytes, io::Error>> + Send + 'static {
    let (sender, receiver) = mpsc::channel::<Bytes>(8);
    let pending = Pending::default();
    let local = exported_at.with_timezone(&Local);
    let mut options = SimpleFileOptions::default().compression_method(CompressionMethod::Deflated);
    if let Ok(time) = zip::DateTime::from_date_and_time(
        u16::try_from(local.year()).unwrap_or(1980),
        local.month() as u8,
        local.day() as u8,
        local.hour() as u8,
        local.minute() as u8,
        local.second() as u8,
    ) {
        options = options.last_modified_time(time);
    }
    let archive = Archive {
        zip: ZipWriter::new_stream(pending.clone()),
        pending,
        options,
        sender,
    };
    tokio::spawn(run(
        db,
        who,
        include_balance_snapshots,
        exported_at,
        archive,
    ));
    futures::stream::unfold(receiver, |mut receiver| async move {
        receiver.recv().await.map(|chunk| (Ok(chunk), receiver))
    })
}

async fn run(
    db: Db,
    who: Exporting,
    include_balance_snapshots: bool,
    exported_at: DateTime<Utc>,
    mut archive: Archive,
) {
    tracing::info!(
        user_id = who.user_id,
        exporters = EXPORTERS.len(),
        include_balance_snapshots,
        "export.start"
    );
    let outcome = write_all(
        &db,
        &who,
        include_balance_snapshots,
        exported_at,
        &mut archive,
    )
    .await;
    match outcome {
        Ok(()) => tracing::info!(user_id = who.user_id, "export.completed"),
        Err(ExportError::Gone) => tracing::debug!(user_id = who.user_id, "export.client-aborted"),
        Err(error) => {
            tracing::error!(user_id = who.user_id, %error, "export.failed");
            if let Err(marker) = write_failure_marker(&mut archive).await {
                tracing::warn!(%marker, "export.failure-marker-write-failed: archive is incomplete AND unmarked");
            }
        }
    }
}

async fn write_all(
    db: &Db,
    who: &Exporting,
    include_balance_snapshots: bool,
    exported_at: DateTime<Utc>,
    archive: &mut Archive,
) -> Result<(), ExportError> {
    // A read snapshot, not db.begin(): the archive is written at the pace its reader downloads
    // it, and on SQLite a transaction of the writer would keep every write waiting meanwhile.
    let mut tx = db.begin_read().await?;
    write_json(
        &mut tx.as_handle(),
        who,
        include_balance_snapshots,
        exported_at,
        archive,
    )
    .await?;
    write_csv_entries(&mut tx.as_handle(), who, include_balance_snapshots, archive).await?;
    write_readme(who, exported_at, archive)?;
    tx.commit().await?;
    finish(archive).await
}

async fn finish(archive: &mut Archive) -> Result<(), ExportError> {
    let zip = std::mem::replace(&mut archive.zip, ZipWriter::new_stream(Pending::default()));
    zip.finish()?;
    archive.flush().await
}

async fn write_json(
    conn: &mut DbHandle<'_>,
    who: &Exporting,
    include_balance_snapshots: bool,
    exported_at: DateTime<Utc>,
    archive: &mut Archive,
) -> Result<(), ExportError> {
    archive.start("data.json")?;
    let mut j = JsonWriter::default();
    let out = &mut archive.zip;
    j.start_object(out)?;
    j.entry(out, "schema_version", &Cell::Text("1".into()))?;
    j.entry(out, "exported_at", &Cell::Text(java_instant(exported_at)))?;
    j.entry(out, "user_id", &Cell::Int(who.user_id))?;
    j.entry(
        out,
        "include_balance_snapshots",
        &Cell::Bool(include_balance_snapshots),
    )?;
    for exporter in EXPORTERS
        .iter()
        .filter(|e| e.enabled(include_balance_snapshots))
    {
        j.field(&mut archive.zip, exporter.name())?;
        match exporter {
            Exporter::Table(table) => {
                let names: Vec<&str> = table.columns.iter().map(|(n, _)| *n).collect();
                j.start_array(&mut archive.zip)?;
                for key in exporters::keys(conn, table, who).await? {
                    for row in exporters::rows(conn, table, key).await? {
                        j.row(&mut archive.zip, &names, &row)?;
                    }
                    archive.flush().await?;
                }
                j.end_array(&mut archive.zip)?;
            }
            Exporter::Goals => write_goals_json(conn, who, &mut j, archive).await?,
            Exporter::Profile => {
                let cells = exporters::profile(conn, who).await?;
                let (user, member) = exporters::profile_parts(&cells);
                let out = &mut archive.zip;
                j.start_object(out)?;
                for (name, cell) in user {
                    j.entry(out, name, cell)?;
                }
                j.field(out, "member")?;
                j.start_object(out)?;
                for (name, cell) in member {
                    j.entry(out, name, cell)?;
                }
                j.end_object(out)?;
                j.end_object(out)?;
            }
        }
        archive.flush().await?;
    }
    j.end_object(&mut archive.zip)?;
    Ok(())
}

async fn write_goals_json(
    conn: &mut DbHandle<'_>,
    who: &Exporting,
    j: &mut JsonWriter,
    archive: &mut Archive,
) -> Result<(), ExportError> {
    let names: Vec<&str> = GOAL_COLUMNS.iter().map(|(n, _)| *n).collect();
    j.start_array(&mut archive.zip)?;
    for goal in exporters::goals(conn, who.member_id).await? {
        let Cell::Int(goal_id) = goal[0] else {
            return Err(ExportError::Db(sqlx::Error::RowNotFound));
        };
        let out = &mut archive.zip;
        j.start_object(out)?;
        for (name, cell) in names.iter().zip(&goal) {
            j.entry(out, name, cell)?;
        }
        j.field(out, "account_ids")?;
        j.start_array(out)?;
        for id in exporters::goal_account_ids(conn, goal_id).await? {
            j.value(&mut archive.zip, &Cell::Int(id))?;
        }
        j.end_array(&mut archive.zip)?;
        for child in &GOAL_CHILDREN {
            let child_names: Vec<&str> = child.columns.iter().map(|(n, _)| *n).collect();
            let rows = exporters::goal_children(conn, child, goal_id).await?;
            let out = &mut archive.zip;
            j.field(out, child.field)?;
            j.start_array(out)?;
            for row in rows {
                j.row(out, &child_names, &row)?;
            }
            j.end_array(out)?;
        }
        j.end_object(&mut archive.zip)?;
    }
    j.end_array(&mut archive.zip)?;
    Ok(())
}

async fn write_csv_entries(
    conn: &mut DbHandle<'_>,
    who: &Exporting,
    include_balance_snapshots: bool,
    archive: &mut Archive,
) -> Result<(), ExportError> {
    for exporter in EXPORTERS
        .iter()
        .filter(|e| e.enabled(include_balance_snapshots))
    {
        archive.start(&format!("{}.csv", exporter.name()))?;
        archive.zip.write_all("\u{FEFF}".as_bytes())?;
        write_csv_row(
            &mut archive.zip,
            exporter.csv_header().into_iter().map(str::to_owned),
        )?;
        match exporter {
            Exporter::Table(table) => {
                for key in exporters::keys(conn, table, who).await? {
                    for row in exporters::rows(conn, table, key).await? {
                        write_csv_row(&mut archive.zip, row.iter().map(Cell::csv))?;
                    }
                    archive.flush().await?;
                }
            }
            Exporter::Goals => {
                for mut goal in exporters::goals(conn, who.member_id).await? {
                    let Cell::Int(goal_id) = goal[0] else {
                        return Err(ExportError::Db(sqlx::Error::RowNotFound));
                    };
                    let ids = exporters::goal_account_ids(conn, goal_id)
                        .await?
                        .iter()
                        .map(i64::to_string)
                        .collect::<Vec<_>>()
                        .join(";");
                    goal.insert(9, Cell::Text(ids));
                    write_csv_row(&mut archive.zip, goal.iter().map(Cell::csv))?;
                }
            }
            Exporter::Profile => {
                let cells = exporters::profile(conn, who).await?;
                write_csv_row(&mut archive.zip, cells.iter().map(Cell::csv))?;
            }
        }
        archive.flush().await?;
    }
    Ok(())
}

/// CsvWriter.writeRow: RFC 4180 quoting, CRLF line ends.
fn write_csv_row(out: &mut impl Write, fields: impl Iterator<Item = String>) -> io::Result<()> {
    let line = fields.map(|f| csv_escape(&f)).collect::<Vec<_>>().join(",");
    out.write_all(line.as_bytes())?;
    out.write_all(b"\r\n")
}

fn csv_escape(field: &str) -> String {
    if field.contains([',', '"', '\n', '\r']) {
        format!("\"{}\"", field.replace('"', "\"\""))
    } else {
        field.to_owned()
    }
}

fn write_readme(
    who: &Exporting,
    exported_at: DateTime<Utc>,
    archive: &mut Archive,
) -> Result<(), ExportError> {
    archive.start("README.txt")?;
    archive.zip.write_all(readme(who, exported_at).as_bytes())?;
    Ok(())
}

fn readme(who: &Exporting, exported_at: DateTime<Utc>) -> String {
    format!(
        "Picsou data export
==================

Exported at:   {}
User id:       {}
Username:      {}

Files
-----
data.json       \u{2014} complete export, machine-readable
*.csv           \u{2014} per-entity tables, spreadsheet-friendly (UTF-8 with BOM)
README.txt      \u{2014} this file

What is excluded
----------------
For your own security, the following are deliberately NOT exported:
  - your password hash and activation token
  - your TOTP secret and MFA recovery codes
  - bank-connection refresh/access tokens and encrypted credentials
    (including SimpleFIN access URLs and Wise API tokens)
  - long-lived session tokens

All other data we hold for your account is included in this archive
(GDPR Art. 15 \u{2014} right of access).
",
        java_instant(exported_at),
        who.user_id,
        who.username
    )
}

async fn write_failure_marker(archive: &mut Archive) -> Result<(), ExportError> {
    archive.start("__EXPORT_FAILED__.txt")?;
    archive.zip.write_all(
        "Export failed mid-stream \u{2014} the archive is incomplete. \
         Please retry; if the problem persists, contact the administrator.\n"
            .as_bytes(),
    )?;
    finish(archive).await
}

#[cfg(test)]
mod tests {
    use super::*;

    fn csv(rows: &[&[&str]]) -> String {
        let mut out = Vec::new();
        for row in rows {
            write_csv_row(&mut out, row.iter().map(|s| (*s).to_owned())).unwrap();
        }
        String::from_utf8(out).unwrap()
    }

    #[test]
    fn csv_rows_end_with_crlf_and_quote_only_when_needed() {
        assert_eq!(csv(&[&["a", "b"], &["1", "2"]]), "a,b\r\n1,2\r\n");
        assert_eq!(csv(&[&["hello, world", "x"]]), "\"hello, world\",x\r\n");
        assert_eq!(csv(&[&["she said \"hi\""]]), "\"she said \"\"hi\"\"\"\r\n");
        assert_eq!(csv(&[&["line1\nline2"]]), "\"line1\nline2\"\r\n");
        assert_eq!(csv(&[&["", "x"]]), ",x\r\n");
    }

    #[test]
    fn readme_matches_the_java_text_block() {
        let who = Exporting {
            user_id: 7,
            username: "chloe".into(),
            member_id: 42,
        };
        let at = DateTime::parse_from_rfc3339("2026-03-15T10:00:00Z")
            .unwrap()
            .with_timezone(&Utc);
        let text = readme(&who, at);
        assert!(text.starts_with("Picsou data export\n==================\n\nExported at:   2026-03-15T10:00:00Z\nUser id:       7\nUsername:      chloe\n"));
        assert!(text.contains("(including SimpleFIN access URLs and Wise API tokens)"));
        assert!(text.ends_with("(GDPR Art. 15 \u{2014} right of access).\n"));
    }
}
