//! Tracing to stdout, plus a size-bounded rolling file when LOGGING_FILE_NAME or
//! LOGGING_FILE_PATH is set (Spring Boot's file appender: 10 MB files, seven kept).

use std::fs::{self, File, OpenOptions};
use std::io::{self, Write};
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex, OnceLock};

use tracing_subscriber::EnvFilter;
use tracing_subscriber::layer::SubscriberExt;
use tracing_subscriber::util::SubscriberInitExt;

const MAX_FILE_BYTES: u64 = 10 * 1024 * 1024;
const KEPT_FILES: usize = 7;

struct Rolling {
    path: PathBuf,
    file: File,
    written: u64,
}

impl Rolling {
    fn open(path: &Path) -> io::Result<Self> {
        if let Some(parent) = path.parent() {
            fs::create_dir_all(parent)?;
        }
        let file = OpenOptions::new().create(true).append(true).open(path)?;
        let written = file.metadata()?.len();
        Ok(Self {
            path: path.to_path_buf(),
            file,
            written,
        })
    }

    fn rotate(&mut self) -> io::Result<()> {
        let numbered = |i: usize| PathBuf::from(format!("{}.{i}", self.path.display()));
        let _ = fs::remove_file(numbered(KEPT_FILES));
        for i in (1..KEPT_FILES).rev() {
            let _ = fs::rename(numbered(i), numbered(i + 1));
        }
        fs::rename(&self.path, numbered(1))?;
        self.file = OpenOptions::new()
            .create(true)
            .append(true)
            .open(&self.path)?;
        self.written = 0;
        Ok(())
    }
}

/// A file writer rotating at MAX_FILE_BYTES, keeping KEPT_FILES old files.
#[derive(Clone)]
pub struct RollingFile(Arc<Mutex<Rolling>>);

impl RollingFile {
    pub fn open(path: &Path) -> io::Result<Self> {
        Ok(Self(Arc::new(Mutex::new(Rolling::open(path)?))))
    }
}

impl Write for RollingFile {
    fn write(&mut self, buf: &[u8]) -> io::Result<usize> {
        let mut inner = self.0.lock().unwrap_or_else(|e| e.into_inner());
        if inner.written + buf.len() as u64 > MAX_FILE_BYTES && inner.written > 0 {
            inner.rotate()?;
        }
        let n = inner.file.write(buf)?;
        inner.written += n as u64;
        Ok(n)
    }

    fn flush(&mut self) -> io::Result<()> {
        self.0
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .file
            .flush()
    }
}

static FILE_GUARD: OnceLock<tracing_appender::non_blocking::WorkerGuard> = OnceLock::new();

/// Installs the global subscriber once; later calls are ignored (tests start many apps).
pub fn init(log_file: Option<&Path>) {
    let filter =
        EnvFilter::try_from_env("RUST_LOG").unwrap_or_else(|_| EnvFilter::new("info,sqlx=warn"));
    let stdout = tracing_subscriber::fmt::layer().with_target(false);
    let file = log_file.and_then(|path| match RollingFile::open(path) {
        Ok(writer) => {
            let (writer, guard) = tracing_appender::non_blocking(writer);
            let _ = FILE_GUARD.set(guard);
            Some(
                tracing_subscriber::fmt::layer()
                    .with_ansi(false)
                    .with_writer(writer),
            )
        }
        Err(e) => {
            eprintln!("cannot open log file {}: {e}", path.display());
            None
        }
    });
    let _ = tracing_subscriber::registry()
        .with(filter)
        .with(stdout)
        .with(file)
        .try_init();
}
