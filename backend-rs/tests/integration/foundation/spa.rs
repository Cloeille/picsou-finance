use picsou::testing::TestApp;
use reqwest::StatusCode;

fn site() -> std::path::PathBuf {
    let dir = std::env::temp_dir().join(format!("picsou-spa-{}", uuid::Uuid::new_v4()));
    std::fs::create_dir_all(dir.join("assets")).unwrap();
    std::fs::write(
        dir.join("index.html"),
        "<!doctype html><title>Picsou</title><div id=root></div>",
    )
    .unwrap();
    std::fs::write(dir.join("assets/index-abc123.js"), "console.log('picsou')").unwrap();
    std::fs::write(dir.join("favicon.svg"), "<svg/>").unwrap();
    std::fs::write(dir.with_extension("secret"), "nope").unwrap();
    dir
}

#[tokio::test]
async fn serves_the_app_with_index_fallback() {
    let dir = site();
    let app = TestApp::builder()
        .env("SPA_LOCATION", dir.display().to_string())
        .spawn()
        .await;
    let client = app.client();
    let root = client.get("/").send().await;
    assert_eq!(root.status, StatusCode::OK);
    assert!(root.text().contains("<title>Picsou</title>"));
    assert_eq!(root.header("cache-control"), Some("no-cache"));
    let etag = root.header("etag").unwrap().to_owned();
    assert!(etag.starts_with("W/\""));
    let route = client.get("/settings/security").send().await;
    assert_eq!(
        route.text(),
        root.text(),
        "client routes get index.html, even before setup"
    );
    let unchanged = client.get("/").header("if-none-match", &etag).send().await;
    assert_eq!(unchanged.status, StatusCode::NOT_MODIFIED);

    let asset = client.get("/assets/index-abc123.js").send().await;
    assert_eq!(asset.status, StatusCode::OK);
    assert_eq!(
        asset.header("cache-control"),
        Some("max-age=31536000, public, immutable")
    );
    assert!(asset.header("content-type").unwrap().contains("javascript"));
    assert_eq!(
        client.get("/assets/missing.js").send().await.status,
        StatusCode::NOT_FOUND
    );
    assert_eq!(
        client
            .get("/favicon.svg")
            .send()
            .await
            .header("cache-control"),
        Some("no-cache")
    );
    assert_eq!(
        client.get("/api/whatever").send().await.status,
        StatusCode::SERVICE_UNAVAILABLE,
        "backend paths never fall back"
    );
    let name = dir
        .with_extension("secret")
        .file_name()
        .unwrap()
        .to_string_lossy()
        .into_owned();
    assert!(
        !client
            .get(&format!("/%2e%2e/{name}"))
            .send()
            .await
            .text()
            .contains("nope")
    );
    assert!(
        !client
            .get(&format!("/..%2f{name}"))
            .send()
            .await
            .text()
            .contains("nope")
    );
    let _ = std::fs::remove_file(dir.with_extension("secret"));
    let _ = std::fs::remove_dir_all(dir);
}
