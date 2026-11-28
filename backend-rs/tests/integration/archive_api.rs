//! The HTTP side of the archive (#865, slice 2): import from the first screen, applied at the
//! next start, and export from the admin area.

use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::time::Duration;

use picsou::clock::FixedClock;
use picsou::config::{Config, Env};
use picsou::db::archive::{self, ImportOutcome, STAGING_DIR};
use picsou::testing::{
    ADMIN_PASSWORD, ADMIN_USERNAME, CRYPTO_KEY as APP_KEY, DB_HOST, DB_PORT, DB_USER, TestApp,
    epoch, fresh_database,
};
use reqwest::StatusCode;
use serde_json::json;

use super::archive::{CRYPTO_KEY as OTHER_KEY, Instance, PASSPHRASE, export_sqlite};

const BOUNDARY: &str = "----archive-api-test";

fn multipart(archive: Option<&[u8]>, passphrase: Option<&str>) -> (String, Vec<u8>) {
    let mut body = Vec::new();
    if let Some(bytes) = archive {
        body.extend_from_slice(
            format!(
                "--{BOUNDARY}\r\nContent-Disposition: form-data; name=\"archive\"; filename=\"a.picsou-archive\"\r\nContent-Type: application/octet-stream\r\n\r\n"
            )
            .as_bytes(),
        );
        body.extend_from_slice(bytes);
        body.extend_from_slice(b"\r\n");
    }
    if let Some(value) = passphrase {
        body.extend_from_slice(
            format!(
                "--{BOUNDARY}\r\nContent-Disposition: form-data; name=\"passphrase\"\r\n\r\n{value}\r\n"
            )
            .as_bytes(),
        );
    }
    body.extend_from_slice(format!("--{BOUNDARY}--\r\n").as_bytes());
    (format!("multipart/form-data; boundary={BOUNDARY}"), body)
}

async fn post_import(
    client: &picsou::testing::Client,
    archive: Option<&[u8]>,
    passphrase: Option<&str>,
) -> picsou::testing::Response {
    let (content_type, body) = multipart(archive, passphrase);
    client
        .post("/api/setup/import-archive")
        .body(&content_type, body)
        .send()
        .await
}

/// A sealed archive of an instance holding `alice`, whose encryption key is `key`.
async fn sealed_archive(tag: &str, key: &[u8]) -> (Instance, Vec<u8>) {
    let source = Instance::new(tag);
    source.write_secrets(key);
    source.seed_sqlite(&["alice"]);
    let out = source.root.join("out.picsou-archive");
    export_sqlite(&source, &out).await;
    let bytes = std::fs::read(&out).expect("read the archive");
    (source, bytes)
}

/// A test app whose archive routes have a data folder on both engines.
async fn app_with(extra: &[(&str, &str)]) -> (TestApp, Instance) {
    let home = Instance::new("app");
    let mut builder =
        TestApp::builder().env("PICSOU_SECRETS_DIR", home.secrets().display().to_string());
    for (key, value) in extra {
        builder = builder.env(key, *value);
    }
    (builder.spawn().await, home)
}

fn data_dir(app: &TestApp) -> PathBuf {
    app.state.config.data_dir().expect("a data folder")
}

fn mode(path: &Path) -> u32 {
    use std::os::unix::fs::PermissionsExt;
    std::fs::metadata(path)
        .expect("metadata")
        .permissions()
        .mode()
        & 0o777
}

fn no_scratch_left(dir: &Path) -> bool {
    std::fs::read_dir(dir)
        .expect("read the data folder")
        .filter_map(Result::ok)
        .all(|e| {
            !e.file_name()
                .to_string_lossy()
                .starts_with(".picsou-archive-")
        })
}

async fn user_count(app: &TestApp) -> i64 {
    picsou::db::query_scalar("SELECT count(*) FROM app_user")
        .fetch_one(&app.state.db)
        .await
        .expect("count users")
}

// ---- POST /api/setup/import-archive ----------------------------------------------------------

/// A refused upload: what it is, the file, the passphrase, the status and the code expected.
type Case<'a> = (
    &'a str,
    Option<&'a [u8]>,
    Option<&'a str>,
    StatusCode,
    &'a str,
);

#[tokio::test]
async fn an_import_is_staged_and_a_restart_requested_without_touching_the_data() {
    let (app, _home) = app_with(&[]).await;
    let (_source, archive) = sealed_archive("import_ok", APP_KEY.as_bytes()).await;
    let client = app.client();

    let res = post_import(&client, Some(&archive), Some(PASSPHRASE)).await;
    assert_eq!(res.status, StatusCode::ACCEPTED, "{}", res.text());
    let body = res.json();
    assert_eq!(body["restarting"], true);
    assert!(body["tables"].as_u64().unwrap() > 0);
    assert!(body["rows"].as_u64().unwrap() > 0);

    let staging = data_dir(&app).join(STAGING_DIR);
    assert!(staging.join("archive.zip").is_file());
    assert_eq!(mode(&staging), 0o700);
    assert_eq!(mode(&staging.join("archive.zip")), 0o600);
    // What is staged is the plain zip: the passphrase is not needed again, and is not kept.
    let staged = std::fs::read(staging.join("archive.zip")).expect("staged");
    assert_eq!(&staged[..2], b"PK");
    assert!(no_scratch_left(&data_dir(&app)));

    assert!(app.state.restart.is_requested());
    assert_eq!(user_count(&app).await, 0, "the live data is untouched");
    let status = client.get("/api/setup/status").send().await.json();
    assert_eq!(status["archiveImport"], json!({"state": "pending"}));

    let again = post_import(&client, Some(&archive), Some(PASSPHRASE)).await;
    assert_eq!(again.status, StatusCode::CONFLICT);
    assert_eq!(again.json()["code"], "IMPORT_ALREADY_STAGED");
}

#[tokio::test]
async fn a_refused_archive_stages_nothing_and_asks_for_no_restart() {
    let (app, _home) = app_with(&[]).await;
    let (_source, archive) = sealed_archive("import_refused", APP_KEY.as_bytes()).await;
    let client = app.client();
    let (_other, foreign) = sealed_archive("import_foreign", OTHER_KEY).await;

    let cases: [Case; 6] = [
        (
            "wrong passphrase",
            Some(&archive),
            Some("another long passphrase"),
            StatusCode::BAD_REQUEST,
            "WRONG_PASSPHRASE",
        ),
        (
            "short passphrase",
            Some(&archive),
            Some("short"),
            StatusCode::BAD_REQUEST,
            "PASSPHRASE_TOO_SHORT",
        ),
        (
            "no passphrase",
            Some(&archive),
            None,
            StatusCode::BAD_REQUEST,
            "PASSPHRASE_REQUIRED",
        ),
        (
            "not an archive",
            Some(b"just some bytes that are no archive at all"),
            Some(PASSPHRASE),
            StatusCode::BAD_REQUEST,
            "NOT_AN_ARCHIVE",
        ),
        (
            "key set by the environment differs",
            Some(&foreign),
            Some(PASSPHRASE),
            StatusCode::CONFLICT,
            "KEY_OVERRIDDEN",
        ),
        (
            "no file",
            None,
            Some(PASSPHRASE),
            StatusCode::BAD_REQUEST,
            "",
        ),
    ];
    for (what, file, passphrase, status, code) in cases {
        let res = post_import(&client, file, passphrase).await;
        assert_eq!(res.status, status, "{what}: {}", res.text());
        if !code.is_empty() {
            assert_eq!(res.json()["code"], code, "{what}");
        }
        assert!(
            !res.text().contains(PASSPHRASE),
            "{what}: the passphrase is never echoed"
        );
        assert!(
            !data_dir(&app).join(STAGING_DIR).exists(),
            "{what}: nothing staged"
        );
        assert!(no_scratch_left(&data_dir(&app)), "{what}");
    }
    assert!(!app.state.restart.is_requested());
    let status = client.get("/api/setup/status").send().await.json();
    assert!(status.get("archiveImport").is_none());
}

#[tokio::test]
async fn an_import_is_refused_once_an_account_exists_or_setup_is_complete() {
    let (app, _home) = app_with(&[]).await;
    let (_source, archive) = sealed_archive("import_late", APP_KEY.as_bytes()).await;
    let client = app.client();

    let admin = client
        .post("/api/setup/admin")
        .json(&json!({"username": ADMIN_USERNAME, "password": ADMIN_PASSWORD}))
        .send()
        .await;
    assert_eq!(admin.status, StatusCode::OK);
    let res = post_import(&client, Some(&archive), Some(PASSPHRASE)).await;
    assert_eq!(res.status, StatusCode::CONFLICT);
    assert_eq!(res.json()["code"], "SETUP_ALREADY_STARTED");

    let complete = client.post("/api/setup/complete").send().await;
    assert_eq!(complete.status, StatusCode::NO_CONTENT);
    let res = post_import(&client, Some(&archive), Some(PASSPHRASE)).await;
    assert_eq!(res.status, StatusCode::GONE);

    assert!(!data_dir(&app).join(STAGING_DIR).exists());
    assert!(!app.state.restart.is_requested());
}

#[tokio::test]
async fn an_import_is_limited_but_not_by_the_one_mebibyte_cap_of_anonymous_routes() {
    let (app, _home) = app_with(&[("PICSOU_ARCHIVE_MAX_UPLOAD_BYTES", "2000000")]).await;
    let client = app.client();

    // 1.5 MiB passes the generic anonymous cap and reaches the archive checks.
    let junk = vec![b'x'; 1_500_000];
    let res = post_import(&client, Some(&junk), Some(PASSPHRASE)).await;
    assert_eq!(res.status, StatusCode::BAD_REQUEST, "{}", res.text());
    assert_eq!(res.json()["code"], "NOT_AN_ARCHIVE");

    // Past the configured limit it is refused, announced or not.
    let big = vec![b'x'; 2_100_000];
    let res = post_import(&client, Some(&big), Some(PASSPHRASE)).await;
    assert_eq!(res.status, StatusCode::PAYLOAD_TOO_LARGE);
    assert_eq!(res.json()["code"], "ARCHIVE_TOO_LARGE");
    assert!(!data_dir(&app).join(STAGING_DIR).exists());

    // Other anonymous setup routes keep their 1 MiB cap.
    let res = client
        .post("/api/setup/admin")
        .body("application/json", vec![b' '; 1_100_000])
        .send()
        .await;
    assert_eq!(res.status, StatusCode::PAYLOAD_TOO_LARGE);
}

#[tokio::test]
async fn an_import_is_rate_limited_like_the_other_setup_routes() {
    let (app, _home) = app_with(&[]).await;
    let client = app.client();
    for _ in 0..10 {
        let res = post_import(&client, Some(b"nothing"), Some(PASSPHRASE)).await;
        assert_eq!(res.status, StatusCode::BAD_REQUEST);
    }
    let res = post_import(&client, Some(b"nothing"), Some(PASSPHRASE)).await;
    assert_eq!(res.status, StatusCode::TOO_MANY_REQUESTS);
}

#[tokio::test]
async fn in_desktop_mode_an_import_needs_the_gate_of_the_apps_own_window() {
    let (app, _home) = app_with(&[("APP_DESKTOP_MODE", "true")]).await;
    let (_source, archive) = sealed_archive("import_desktop", APP_KEY.as_bytes()).await;

    let without = post_import(&app.client(), Some(&archive), Some(PASSPHRASE)).await;
    assert_eq!(without.status, StatusCode::FORBIDDEN);
    assert!(!data_dir(&app).join(STAGING_DIR).exists());

    let with = post_import(&app.desktop_client(), Some(&archive), Some(PASSPHRASE)).await;
    assert_eq!(with.status, StatusCode::ACCEPTED, "{}", with.text());
    assert!(
        data_dir(&app)
            .join(STAGING_DIR)
            .join("archive.zip")
            .is_file()
    );
}

// ---- the start that applies a staged import --------------------------------------------------

fn sqlite_env(instance: &Instance) -> Env {
    let mut env = instance.env();
    for (key, value) in [
        (
            "JWT_SECRET",
            "test-jwt-secret-0123456789-abcdefghijklmnopqrstu".to_owned(),
        ),
        (
            "DATABASE_URL",
            format!(
                "sqlite:///{}",
                instance.db().display().to_string().trim_start_matches('/')
            ),
        ),
        ("SECURE_COOKIES", "false".to_owned()),
        ("APP_STARTUP_SYNC_ENABLED", "false".to_owned()),
        ("APP_UPDATE_CHECK_ENABLED", "false".to_owned()),
        ("INSTRUMENT_LOGOS_ENABLED", "false".to_owned()),
    ] {
        env.set(key, value);
    }
    env
}

fn pg_env(instance: &Instance, database: &str) -> Env {
    let mut env = instance.env();
    for (key, value) in [
        (
            "JWT_SECRET",
            "test-jwt-secret-0123456789-abcdefghijklmnopqrstu".to_owned(),
        ),
        (
            "SPRING_DATASOURCE_URL",
            format!("jdbc:postgresql://{DB_HOST}:{DB_PORT}/{database}"),
        ),
        ("SPRING_DATASOURCE_USERNAME", DB_USER.to_owned()),
        ("SECURE_COOKIES", "false".to_owned()),
        ("APP_STARTUP_SYNC_ENABLED", "false".to_owned()),
        ("APP_UPDATE_CHECK_ENABLED", "false".to_owned()),
        ("INSTRUMENT_LOGOS_ENABLED", "false".to_owned()),
    ] {
        env.set(key, value);
    }
    env
}

async fn start(env: &Env) -> picsou::AppState {
    let config = Config::load(env).expect("the configuration");
    picsou::start(config, Arc::new(FixedClock::new(epoch())))
        .await
        .expect("the start")
}

async fn usernames(state: &picsou::AppState) -> Vec<String> {
    picsou::db::query_scalar("SELECT username FROM app_user ORDER BY username")
        .fetch_all(&state.db)
        .await
        .expect("usernames")
}

fn backups(root: &Path) -> Vec<String> {
    let Ok(entries) = std::fs::read_dir(root.join("backups")) else {
        return Vec::new();
    };
    let mut names: Vec<String> = entries
        .map(|e| e.expect("entry").file_name().to_string_lossy().into_owned())
        .collect();
    names.sort();
    names
}

async fn served(state: &picsou::AppState) -> (String, tokio::sync::oneshot::Sender<()>) {
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0")
        .await
        .expect("bind");
    let base = format!("http://{}", listener.local_addr().expect("addr"));
    let router = picsou::app(state.clone());
    let (stop, stopped) = tokio::sync::oneshot::channel::<()>();
    tokio::spawn(async move {
        let _ = axum::serve(
            listener,
            router.into_make_service_with_connect_info::<std::net::SocketAddr>(),
        )
        .with_graceful_shutdown(async {
            let _ = stopped.await;
        })
        .await;
    });
    (base, stop)
}

#[tokio::test]
async fn the_next_start_applies_a_staged_import_into_sqlite() {
    let (source, archive) = sealed_archive("apply_sqlite_src", APP_KEY.as_bytes()).await;
    drop(source);
    let target = Instance::new("apply_sqlite");
    let env = sqlite_env(&target);
    archive::stage_import(&target.root, archive, PASSPHRASE.to_owned(), &env)
        .await
        .expect("stage");
    assert!(target.root.join(STAGING_DIR).is_dir());
    assert!(
        !target.db().exists(),
        "staging does not create the database"
    );

    let state = start(&env).await;
    assert_eq!(usernames(&state).await, ["alice"]);
    assert!(
        !target.root.join(STAGING_DIR).exists(),
        "staging is removed"
    );
    assert_eq!(
        state.config.crypto_key, APP_KEY,
        "the server runs with the archived key"
    );
    assert_eq!(
        std::fs::read(target.crypto_key()).expect("key file"),
        APP_KEY.as_bytes()
    );
    assert!(matches!(
        state.archive_import_outcome(),
        Some(ImportOutcome::Done { rows, .. }) if rows > 0
    ));
    // The setup state came with the data: the instance was a working one.
    let (base, stop) = served(&state).await;
    let status = picsou::testing::Client::new(&base)
        .get("/api/setup/status")
        .send()
        .await
        .json();
    assert_eq!(status["archiveImport"]["state"], "done");
    assert!(status["archiveImport"]["rows"].as_u64().unwrap() > 0);
    let _ = stop.send(());
    state.db.close().await;
}

#[tokio::test]
async fn the_next_start_applies_a_staged_import_into_postgres() {
    let (source, archive) = sealed_archive("apply_pg_src", APP_KEY.as_bytes()).await;
    drop(source);
    let target = Instance::new("apply_pg");
    let database = fresh_database().await;
    let env = pg_env(&target, database.name());
    archive::stage_import(&target.root, archive, PASSPHRASE.to_owned(), &env)
        .await
        .expect("stage");

    let state = start(&env).await;
    assert!(state.db.is_postgres());
    assert_eq!(usernames(&state).await, ["alice"]);
    assert!(!target.root.join(STAGING_DIR).exists());
    assert_eq!(state.config.crypto_key, APP_KEY);
    assert!(matches!(
        state.archive_import_outcome(),
        Some(ImportOutcome::Done { .. })
    ));
    state.db.close().await;
}

#[tokio::test]
async fn a_failed_apply_keeps_the_previous_data_and_reports_it_once() {
    let (source, archive) = sealed_archive("apply_fail_src", APP_KEY.as_bytes()).await;
    drop(source);
    let target = Instance::new("apply_fail");
    target.seed_sqlite(&["bob"]);
    let env = sqlite_env(&target);
    archive::stage_import(&target.root, archive, PASSPHRASE.to_owned(), &env)
        .await
        .expect("stage");

    // An account was created between the staging and the start: the import must not overwrite it.
    let state = start(&env).await;
    assert_eq!(
        usernames(&state).await,
        ["bob"],
        "the previous data is kept"
    );
    assert!(
        !target.root.join(STAGING_DIR).exists(),
        "never applied twice"
    );
    let aside = backups(&target.root);
    assert_eq!(aside, [format!("{}-failed-import", epoch().timestamp())]);
    assert!(
        target
            .root
            .join("backups")
            .join(&aside[0])
            .join("archive.zip")
            .is_file()
    );
    assert_eq!(
        state.archive_import_outcome(),
        Some(ImportOutcome::Failed {
            code: "TARGET_NOT_EMPTY"
        })
    );

    let (base, stop) = served(&state).await;
    let status = picsou::testing::Client::new(&base)
        .get("/api/setup/status")
        .send()
        .await
        .json();
    assert_eq!(
        status["archiveImport"],
        json!({"state": "failed", "code": "TARGET_NOT_EMPTY"})
    );
    let _ = stop.send(());
    state.db.close().await;

    // The next start has nothing to apply and reports nothing new.
    let again = start(&env).await;
    assert!(again.archive_import_outcome().is_none());
    again.db.close().await;
}

#[tokio::test]
async fn a_corrupt_staging_folder_is_set_aside_and_the_server_still_starts() {
    let target = Instance::new("apply_corrupt");
    let staging = target.root.join(STAGING_DIR);
    std::fs::create_dir_all(&staging).expect("staging");
    std::fs::write(staging.join("archive.zip"), b"not a zip").expect("garbage");
    let env = sqlite_env(&target);

    let state = start(&env).await;
    assert!(matches!(
        state.archive_import_outcome(),
        Some(ImportOutcome::Failed {
            code: "CORRUPT_ARCHIVE"
        })
    ));
    assert!(!staging.exists());
    assert_eq!(backups(&target.root).len(), 1);
    state.db.close().await;
}

#[tokio::test]
async fn a_restart_request_ends_run_with_exit_restart() {
    let target = Instance::new("restart");
    let state = start(&sqlite_env(&target)).await;
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0")
        .await
        .expect("bind");
    let running = tokio::spawn(picsou::run(
        state.clone(),
        listener,
        std::future::pending::<()>(),
    ));
    tokio::time::sleep(Duration::from_millis(100)).await;
    state.restart.request();
    let exit = tokio::time::timeout(Duration::from_secs(15), running)
        .await
        .expect("run returns after a restart request")
        .expect("joined")
        .expect("run");
    assert_eq!(exit, picsou::Exit::Restart);

    let other = Instance::new("stopped");
    let state = start(&sqlite_env(&other)).await;
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0")
        .await
        .expect("bind");
    let exit = picsou::run(state, listener, std::future::ready(()))
        .await
        .expect("run");
    assert_eq!(exit, picsou::Exit::Stopped);
}

// ---- POST /api/admin/archive -----------------------------------------------------------------

#[tokio::test]
async fn an_export_needs_an_admin_and_a_reauthentication() {
    let (app, _home) = app_with(&[]).await;
    app.complete_setup(ADMIN_USERNAME, ADMIN_PASSWORD).await;
    app.create_member("member", "member-password-1").await;
    let body = json!({"passphrase": PASSPHRASE, "reAuth": {"password": ADMIN_PASSWORD}});

    let anonymous = app
        .client()
        .post("/api/admin/archive")
        .json(&body)
        .send()
        .await;
    assert_eq!(anonymous.status, StatusCode::UNAUTHORIZED);

    let member = app.login("member", "member-password-1").await;
    let res = member.post("/api/admin/archive").json(&body).send().await;
    assert_eq!(res.status, StatusCode::FORBIDDEN);

    let admin = app.login_admin().await;
    for (what, request) in [
        ("no proof", json!({"passphrase": PASSPHRASE})),
        (
            "wrong password",
            json!({"passphrase": PASSPHRASE, "reAuth": {"password": "not-the-password"}}),
        ),
    ] {
        let res = admin.post("/api/admin/archive").json(&request).send().await;
        assert_eq!(res.status, StatusCode::UNAUTHORIZED, "{what}");
        assert_eq!(res.json()["code"], "REAUTH_FAILED", "{what}");
    }
    assert!(no_scratch_left(&data_dir(&app)));

    let short = admin
        .post("/api/admin/archive")
        .json(&json!({"passphrase": "short", "reAuth": {"password": ADMIN_PASSWORD}}))
        .send()
        .await;
    assert_eq!(short.status, StatusCode::BAD_REQUEST);
    assert_eq!(short.json()["code"], "PASSPHRASE_TOO_SHORT");
}

#[tokio::test]
async fn an_exported_file_is_a_sealed_archive_the_import_reads_back() {
    let (app, _home) = app_with(&[]).await;
    app.complete_setup(ADMIN_USERNAME, ADMIN_PASSWORD).await;
    let admin = app.login_admin().await;

    let res = admin
        .post("/api/admin/archive")
        .json(&json!({"passphrase": PASSPHRASE, "reAuth": {"password": ADMIN_PASSWORD}}))
        .send()
        .await;
    assert_eq!(res.status, StatusCode::OK, "{}", res.text());
    assert_eq!(
        res.header("content-disposition"),
        Some("attachment; filename=\"picsou-2026-01-15.picsou-archive\"")
    );
    assert_eq!(res.header("cache-control"), Some("no-store"));
    assert_eq!(&res.body[..8], b"PICSOUA1");
    assert!(
        no_scratch_left(&data_dir(&app)),
        "the scratch folder is gone once the file was sent"
    );

    // Wrong passphrase is refused, the right one reads back into a fresh instance.
    let sealed = &res.body;
    assert!(archive::open(sealed, "another long passphrase").is_err());
    let received = Instance::new("export_readback");
    let file = received.root.join("received.picsou-archive");
    std::fs::write(&file, sealed).expect("write the download");
    let target = Instance::new("export_target");
    let report = archive::import_archive(&file, &target.db(), PASSPHRASE, false, &target.env())
        .await
        .expect("import the exported archive");
    assert!(report.rows > 0);
    assert_eq!(target.usernames(), [ADMIN_USERNAME]);
    assert_eq!(
        std::fs::read(target.crypto_key()).expect("key"),
        APP_KEY.as_bytes()
    );
}

#[tokio::test]
async fn in_the_desktop_window_the_quick_start_account_exports_without_a_password() {
    let (app, _home) = app_with(&[("APP_DESKTOP_MODE", "true")]).await;
    let window = app.desktop_client();
    let quick = window
        .post("/api/setup/desktop-quick-start")
        .json(&json!({"displayName": "Camille"}))
        .send()
        .await;
    assert_eq!(quick.status, StatusCode::OK);

    // The window's gate stands for the password nobody knows (reauth::verify, own_window).
    let res = window
        .post("/api/admin/archive")
        .json(&json!({"passphrase": PASSPHRASE}))
        .send()
        .await;
    assert_eq!(res.status, StatusCode::OK, "{}", res.text());
    assert_eq!(&res.body[..8], b"PICSOUA1");
}
