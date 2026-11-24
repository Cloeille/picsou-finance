//! SecretBootstrap: resolves JWT_SECRET, CRYPTO_ENCRYPTION_KEY and the datasource password,
//! generating and persisting the app-owned secrets under PICSOU_SECRETS_DIR.

use std::fs;
use std::io::{self, Write};
use std::path::{Path, PathBuf};
use std::time::{Duration, Instant};

use base64::Engine;
use base64::engine::general_purpose::STANDARD;
use rand::RngExt;

use crate::config::Env;

const DEFAULT_DATASOURCE_PASSWORD: &str = "picsou";
const LOCK_WAIT: Duration = Duration::from_secs(10);

#[derive(Debug, Clone, Default)]
pub struct Resolved {
    pub dir: Option<PathBuf>,
    pub jwt_secret: Option<String>,
    pub crypto_key: Option<String>,
    pub datasource_password: String,
}

#[derive(Debug, thiserror::Error)]
#[error("{0}")]
pub struct SecretError(String);

struct Secret {
    variable: &'static str,
    file: &'static str,
    /// Size of a generated value in bytes; 0 for a secret the app never generates.
    generated_bytes: usize,
}

const JWT: Secret = Secret {
    variable: "JWT_SECRET",
    file: "jwt_secret",
    generated_bytes: 48,
};
const CRYPTO: Secret = Secret {
    variable: "CRYPTO_ENCRYPTION_KEY",
    file: "crypto_key",
    generated_bytes: 32,
};
const POSTGRES: Secret = Secret {
    variable: "POSTGRES_PASSWORD",
    file: "postgres_password",
    generated_bytes: 0,
};

fn non_empty<'a>(env: &'a Env, key: &str) -> Option<&'a str> {
    env.raw(key).filter(|v| !v.is_empty())
}

pub fn bootstrap(env: &Env) -> Result<Resolved, SecretError> {
    let dir = non_empty(env, "PICSOU_SECRETS_DIR").map(PathBuf::from);
    let mut resolved = Resolved {
        dir: dir.clone(),
        ..Resolved::default()
    };
    let mut postgres = non_empty(env, POSTGRES.variable).map(str::to_owned);
    match &dir {
        Some(dir) => {
            prepare_directory(dir)?;
            resolved.jwt_secret = Some(resolve_generated(env, dir, &JWT)?);
            resolved.crypto_key = Some(resolve_generated(env, dir, &CRYPTO)?);
            if postgres.is_none() && non_empty(env, "SPRING_DATASOURCE_PASSWORD").is_none() {
                let file = dir.join(POSTGRES.file);
                if file.exists() {
                    postgres = Some(read(&file, POSTGRES.variable)?);
                }
            }
        }
        None => {
            resolved.jwt_secret = env.raw(JWT.variable).map(str::to_owned);
            resolved.crypto_key = env.raw(CRYPTO.variable).map(str::to_owned);
        }
    }
    resolved.datasource_password = non_empty(env, "SPRING_DATASOURCE_PASSWORD")
        .map(str::to_owned)
        .or(postgres)
        .unwrap_or_else(|| DEFAULT_DATASOURCE_PASSWORD.to_owned());
    Ok(resolved)
}

fn resolve_generated(env: &Env, dir: &Path, secret: &Secret) -> Result<String, SecretError> {
    let file = dir.join(secret.file);
    if let Some(supplied) = non_empty(env, secret.variable) {
        if supplied.ends_with('\n') {
            // Used verbatim while the variable is set, but read back without the newline once it
            // is removed: the key would change then.
            tracing::warn!(
                "{} ends with a newline. It is used as is, but the copy in {} is read without it if the variable is removed later. Remove the newline.",
                secret.variable,
                file.display()
            );
        }
        write_if_absent(&file, supplied)?;
        return Ok(supplied.to_owned());
    }
    if !file.exists() {
        let mut bytes = vec![0u8; secret.generated_bytes];
        rand::rng().fill(&mut bytes[..]);
        let generated = STANDARD.encode(bytes);
        if write_if_absent(&file, &generated)? {
            tracing::info!("generated {} at {}", secret.variable, file.display());
            return Ok(generated);
        }
    }
    read(&file, secret.variable)
}

fn prepare_directory(dir: &Path) -> Result<(), SecretError> {
    fs::create_dir_all(dir)
        .map_err(|_| SecretError(format!("Cannot create secrets directory {}", dir.display())))?;
    try_chmod(dir, 0o700);
    Ok(())
}

fn read(file: &Path, variable: &str) -> Result<String, SecretError> {
    let content = fs::read_to_string(file).map_err(|_| {
        SecretError(format!(
            "Cannot read secret file {} for {variable}",
            file.display()
        ))
    })?;
    let trimmed = content.trim_end_matches('\n');
    if trimmed.is_empty() {
        return Err(SecretError(format!(
            "Secret file {} is empty: refusing to start with a blank {variable}",
            file.display()
        )));
    }
    Ok(trimmed.to_owned())
}

/// Writes the value unless the file exists, never replacing one: a synced temp file is
/// hard-linked into place, or renamed under a lock file where hard links are unsupported.
fn write_if_absent(file: &Path, value: &str) -> Result<bool, SecretError> {
    if file.exists() {
        return Ok(false);
    }
    let fail = |_| SecretError(format!("Cannot write secret file {}", file.display()));
    let parent = file.parent().unwrap_or(Path::new("."));
    let suffix: u64 = rand::rng().random();
    let temp = parent.join(format!(".secret-{suffix:016x}.tmp"));
    let outcome = (|| -> Result<bool, SecretError> {
        {
            let mut handle = fs::OpenOptions::new()
                .write(true)
                .create_new(true)
                .open(&temp)
                .map_err(fail)?;
            try_chmod(&temp, 0o600);
            handle.write_all(value.as_bytes()).map_err(fail)?;
            handle.sync_all().map_err(fail)?;
        }
        match fs::hard_link(&temp, file) {
            Ok(()) => Ok(true),
            Err(e) if e.kind() == io::ErrorKind::AlreadyExists => Ok(false),
            Err(_) => publish_under_lock(file, &temp),
        }
    })();
    let _ = fs::remove_file(&temp);
    outcome
}

fn publish_under_lock(file: &Path, temp: &Path) -> Result<bool, SecretError> {
    let mut lock_name = file.file_name().unwrap_or_default().to_os_string();
    lock_name.push(".lock");
    let lock = file.with_file_name(lock_name);
    let deadline = Instant::now() + LOCK_WAIT;
    loop {
        match fs::OpenOptions::new()
            .write(true)
            .create_new(true)
            .open(&lock)
        {
            Ok(_) => break,
            Err(e) if e.kind() == io::ErrorKind::AlreadyExists => {}
            Err(_) => {
                return Err(SecretError(format!(
                    "Cannot write secret file {}",
                    file.display()
                )));
            }
        }
        if file.symlink_metadata().is_ok() {
            return Ok(false);
        }
        if Instant::now() >= deadline {
            return Err(SecretError(format!(
                "Secret file {} is still being created behind {}. Delete {} if no other Picsou instance is starting.",
                file.display(),
                lock.display(),
                lock.display()
            )));
        }
        std::thread::sleep(Duration::from_millis(50));
    }
    let result = if file.symlink_metadata().is_ok() {
        Ok(false)
    } else {
        fs::rename(temp, file)
            .map(|()| true)
            .map_err(|_| SecretError(format!("Cannot write secret file {}", file.display())))
    };
    let _ = fs::remove_file(&lock);
    result
}

fn try_chmod(path: &Path, mode: u32) {
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        if fs::set_permissions(path, fs::Permissions::from_mode(mode)).is_err() {
            tracing::warn!(
                "could not chmod {:o} {}: the filesystem may not support it. Secrets may be readable by other users on the host.",
                mode,
                path.display()
            );
        }
    }
    #[cfg(not(unix))]
    {
        let _ = (path, mode);
    }
}
