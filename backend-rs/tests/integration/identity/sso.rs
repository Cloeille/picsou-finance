//! SsoLoginIntegrationTest's twenty scenarios, against the fake IdP.

use std::collections::HashMap;

use chrono::{Duration, Utc};
use picsou::testing::{Client, Response, TestApp};
use reqwest::StatusCode;
use serde_json::json;

use super::fake_idp::{FakeIdp, Fault, claims, s256};

const START: &str = "/oauth2/sso/authorization/oidc";
const CALLBACK: &str = "/oauth2/sso/code/oidc";
const DCR_REDIRECT: &str = "https://claude.ai/api/mcp/auth_callback";
const VERIFIER: &str = "a-deterministic-pkce-verifier-with-at-least-43-characters";
const LOCAL_PASSWORD: &str = "local-password";

struct Sso {
    app: TestApp,
    idp: FakeIdp,
}

struct Flow {
    params: HashMap<String, String>,
    code: String,
}

fn form(pairs: &[(&str, &str)]) -> String {
    url::form_urlencoded::Serializer::new(String::new())
        .extend_pairs(pairs)
        .finish()
}

fn query(location: &str) -> HashMap<String, String> {
    let q = location.split_once('?').map(|(_, q)| q).unwrap_or_default();
    url::form_urlencoded::parse(q.as_bytes())
        .into_owned()
        .collect()
}

impl Sso {
    /// SSO on, the admin group set, setup still pending.
    async fn pending() -> Self {
        let idp = FakeIdp::start().await;
        let app = TestApp::builder()
            .env("SSO_ISSUER_URI", idp.issuer())
            .env("SSO_CLIENT_ID", "picsou-test")
            .env("SSO_CLIENT_SECRET", "test-secret")
            .env("SSO_ADMIN_GROUP", "picsou-admin")
            .spawn()
            .await;
        idp.set_now(app.clock.now_utc());
        Self { app, idp }
    }

    /// Setup completed by alice through the SSO first-admin flow.
    async fn ready() -> Self {
        let sso = Self::pending().await;
        let alice = sso.app.client();
        let result = sso.login(&alice, "setup", &[], "alice", true).await;
        assert_eq!(sso.path(&result), "/setup/security");
        let complete = alice.post("/api/setup/complete").send().await;
        assert_eq!(complete.status, StatusCode::NO_CONTENT);
        sso
    }

    fn path(&self, response: &Response) -> String {
        let location = response.header("location").expect("a redirect").to_owned();
        location
            .strip_prefix(&self.app.base_url)
            .map(str::to_owned)
            .unwrap_or(location)
    }

    async fn start(&self, client: &Client, intent: &str, extra: &[(&str, &str)]) -> Response {
        let mut pairs = vec![("intent", intent)];
        pairs.extend_from_slice(extra);
        client
            .post(START)
            .header("sec-fetch-site", "same-origin")
            .body("application/x-www-form-urlencoded", form(&pairs))
            .send()
            .await
    }

    async fn begin(
        &self,
        client: &Client,
        intent: &str,
        extra: &[(&str, &str)],
        subject: &str,
        admin: bool,
    ) -> Flow {
        let response = self.start(client, intent, extra).await;
        assert_eq!(
            response.status,
            StatusCode::FOUND,
            "start: {}",
            response.text()
        );
        let location = response.header("location").unwrap().to_owned();
        assert!(
            location.starts_with(&format!("{}/authorize?", self.idp.issuer())),
            "{location}"
        );
        let params = query(&location);
        let code = self.idp.issue_code(
            params.clone(),
            subject,
            claims(subject, admin),
            self.app.clock.now_utc(),
        );
        Flow { params, code }
    }

    async fn finish(&self, client: &Client, flow: &Flow) -> Response {
        let response = client
            .post(CALLBACK)
            .header("sec-fetch-site", "same-origin")
            .body(
                "application/x-www-form-urlencoded",
                form(&[("code", &flow.code), ("state", &flow.params["state"])]),
            )
            .send()
            .await;
        assert_eq!(
            response.status,
            StatusCode::FOUND,
            "callback: {}",
            response.text()
        );
        response
    }

    async fn login(
        &self,
        client: &Client,
        intent: &str,
        extra: &[(&str, &str)],
        subject: &str,
        admin: bool,
    ) -> Response {
        let flow = self.begin(client, intent, extra, subject, admin).await;
        self.finish(client, &flow).await
    }

    async fn user(&self, username: &str) -> picsou::auth::AppUser {
        picsou::auth::users::find_by_username(&self.app.state.db, username)
            .await
            .unwrap()
            .expect("user")
    }

    async fn count(&self, sql: &str) -> i64 {
        sqlx::query_scalar(sql)
            .fetch_one(&self.app.state.db)
            .await
            .unwrap()
    }

    async fn identities(&self, user_id: i64) -> i64 {
        sqlx::query_scalar("SELECT count(*) FROM user_identity WHERE user_id = $1")
            .bind(user_id)
            .fetch_one(&self.app.state.db)
            .await
            .unwrap()
    }

    async fn local_user(&self, name: &str) -> i64 {
        self.app.create_member(name, LOCAL_PASSWORD).await.0
    }

    async fn password_login(&self, client: &Client, name: &str) -> Response {
        client
            .post("/api/auth/login")
            .json(&json!({"username": name, "password": LOCAL_PASSWORD}))
            .send()
            .await
    }

    async fn register_client(&self) -> String {
        let response = self
            .app
            .client()
            .post("/oauth2/register")
            .json(&json!({"client_name": "SSO integration", "redirect_uris": [DCR_REDIRECT], "scope": "accounts:read"}))
            .send()
            .await;
        assert_eq!(response.status, StatusCode::CREATED);
        response.json()["client_id"].as_str().unwrap().to_owned()
    }

    async fn authorize(&self, client_id: &str, access: &str) -> Response {
        let (redirect, scope) = if client_id == "picsou-ios" {
            ("picsou://callback", "read")
        } else {
            (DCR_REDIRECT, "accounts:read")
        };
        let q = form(&[
            ("response_type", "code"),
            ("client_id", client_id),
            ("redirect_uri", redirect),
            ("scope", scope),
            ("state", "app-state"),
            ("code_challenge", &s256(VERIFIER)),
            ("code_challenge_method", "S256"),
        ]);
        let browser = self.app.client();
        browser.set_cookie("access_token", access);
        browser.get(&format!("/oauth2/authorize?{q}")).send().await
    }

    async fn consent(&self, client_id: &str, parked: &Response, access: &str) -> Response {
        let state = query(parked.header("location").unwrap())["state"].clone();
        let browser = self.app.client();
        browser.set_cookie("access_token", access);
        browser
            .post("/oauth2/authorize")
            .body(
                "application/x-www-form-urlencoded",
                form(&[
                    ("client_id", client_id),
                    ("state", &state),
                    ("scope", "accounts:read"),
                ]),
            )
            .send()
            .await
    }

    async fn access(&self, name: &str) -> String {
        let user = self.user(name).await;
        self.app
            .state
            .jwt
            .access_token(&user, Some(self.app.clock.now_utc()))
    }
}

trait NowUtc {
    fn now_utc(&self) -> chrono::DateTime<Utc>;
}

impl NowUtc for std::sync::Arc<picsou::clock::FixedClock> {
    fn now_utc(&self) -> chrono::DateTime<Utc> {
        use picsou::clock::Clock;
        self.now()
    }
}

#[tokio::test]
async fn login_while_pending_admin_never_authorizes() {
    let sso = Sso::pending().await;
    let response = sso.start(&sso.app.client(), "login", &[]).await;
    assert_eq!(sso.path(&response), "/setup");
    assert_eq!(sso.count("SELECT count(*) FROM app_user").await, 0);
}

#[tokio::test]
async fn setup_claims_the_first_admin_once() {
    let sso = Sso::pending().await;
    let alice = sso.app.client();
    let result = sso.login(&alice, "setup", &[], "alice", true).await;
    assert_eq!(sso.path(&result), "/setup/security");
    assert!(alice.cookie("refresh_token").is_some());
    let user = sso.user("alice").await;
    assert_eq!(user.role, picsou::auth::Role::Admin);
    assert!(user.password_hash.is_empty());
    assert!(user.activated);
    assert_eq!(sso.identities(user.id).await, 1);
    let again = sso.start(&sso.app.client(), "setup", &[]).await;
    assert_eq!(
        sso.path(&again),
        "/setup/admin?ssoError=SETUP_ALREADY_CLAIMED"
    );
    assert_eq!(
        alice.post("/api/setup/complete").send().await.status,
        StatusCode::NO_CONTENT
    );
}

#[tokio::test]
async fn setup_outside_the_admin_group_is_refused() {
    let sso = Sso::pending().await;
    let result = sso
        .login(&sso.app.client(), "setup", &[], "mallory", false)
        .await;
    assert_eq!(
        sso.path(&result),
        "/setup/admin?ssoError=NOT_IN_ADMIN_GROUP"
    );
    assert_eq!(sso.count("SELECT count(*) FROM app_user").await, 0);
}

#[tokio::test]
async fn an_unlinked_non_member_is_refused_without_provisioning() {
    let sso = Sso::ready().await;
    let before = sso.count("SELECT count(*) FROM app_user").await;
    let result = sso
        .login(&sso.app.client(), "login", &[], "bob", false)
        .await;
    assert_eq!(sso.path(&result), "/login?ssoError=NOT_LINKED");
    assert_eq!(sso.count("SELECT count(*) FROM app_user").await, before);
}

#[tokio::test]
async fn a_matching_username_and_email_never_links_a_local_account() {
    let sso = Sso::ready().await;
    let carol = sso.local_user("carol").await;
    let result = sso
        .login(&sso.app.client(), "login", &[], "carol", false)
        .await;
    assert_eq!(sso.path(&result), "/login?ssoError=NOT_LINKED");
    assert_eq!(sso.identities(carol).await, 0);
}

#[tokio::test]
async fn the_admin_group_provisions_a_passwordless_admin() {
    let sso = Sso::ready().await;
    let result = sso
        .login(&sso.app.client(), "login", &[], "dave", true)
        .await;
    assert!(
        sso.path(&result)
            .starts_with("/login/sso-complete?redirect=")
    );
    let dave = sso.user("dave").await;
    assert_eq!(dave.role, picsou::auth::Role::Admin);
    assert!(dave.password_hash.is_empty());
    assert_eq!(sso.identities(dave.id).await, 1);
    let password = sso.password_login(&sso.app.client(), "dave").await;
    assert_eq!(password.status, StatusCode::UNAUTHORIZED);
}

#[tokio::test]
async fn leaving_the_group_demotes_but_the_last_admin_stays() {
    let sso = Sso::ready().await;
    sso.login(&sso.app.client(), "login", &[], "dave", true)
        .await;
    let demoted = sso
        .login(&sso.app.client(), "login", &[], "dave", false)
        .await;
    assert!(sso.path(&demoted).starts_with("/login/sso-complete?"));
    assert_eq!(sso.user("dave").await.role, picsou::auth::Role::Member);
    let kept = sso
        .login(&sso.app.client(), "login", &[], "alice", false)
        .await;
    assert!(sso.path(&kept).starts_with("/login/sso-complete?"));
    assert_eq!(sso.user("alice").await.role, picsou::auth::Role::Admin);
}

#[tokio::test]
async fn pkce_and_nonce_round_trip_and_tampering_fails() {
    let sso = Sso::ready().await;
    sso.login(&sso.app.client(), "login", &[], "dave", true)
        .await;
    let client = sso.app.client();
    let flow = sso.begin(&client, "login", &[], "dave", true).await;
    let done = sso.finish(&client, &flow).await;
    assert!(sso.path(&done).starts_with("/login/sso-complete?"));
    assert_eq!(flow.params["code_challenge_method"], "S256");
    assert!(!flow.params["nonce"].is_empty());
    let exchange = sso.idp.exchange(&flow.code).unwrap();
    assert_eq!(
        flow.params["code_challenge"],
        s256(&exchange["code_verifier"])
    );
    sso.idp.tamper_next_verifier();
    let tampered = sso
        .login(&sso.app.client(), "login", &[], "dave", true)
        .await;
    assert_eq!(sso.path(&tampered), "/login?ssoError=IDP_ERROR");
    sso.idp.tamper_next_nonce();
    let nonce = sso
        .login(&sso.app.client(), "login", &[], "dave", true)
        .await;
    assert_eq!(sso.path(&nonce), "/login?ssoError=IDP_ERROR");
}

#[tokio::test]
async fn remember_me_restores_sso_but_never_trusts_a_password_login() {
    let sso = Sso::ready().await;
    let carol = sso.local_user("carol").await;
    sqlx::query("INSERT INTO user_identity (user_id, issuer, subject, email) VALUES ($1, $2, 'carol', 'carol@example.test')")
        .bind(carol)
        .bind(sso.idp.issuer())
        .execute(&sso.app.state.db)
        .await
        .unwrap();
    sqlx::query(
        "INSERT INTO user_mfa (user_id, enabled, totp_secret_enc) VALUES ($1, true, 'unused')",
    )
    .bind(carol)
    .execute(&sso.app.state.db)
    .await
    .unwrap();
    let browser = sso.app.client();
    sso.login(&browser, "login", &[("rememberMe", "1")], "carol", false)
        .await;
    let persistent = browser
        .cookie("persistent_token")
        .expect("a remember-me cookie");
    let series = persistent.split(':').next().unwrap().to_owned();
    let (sso_at, trusted): (Option<chrono::DateTime<Utc>>, bool) = sqlx::query_as(
        "SELECT sso_authenticated_at, trusted_for_2fa FROM persistent_session WHERE series_id::text = $1",
    )
    .bind(&series)
    .fetch_one(&sso.app.state.db)
    .await
    .unwrap();
    assert!(sso_at.is_some());
    assert!(!trusted);
    let restored = sso.app.client();
    restored.set_cookie("persistent_token", &persistent);
    let refresh = restored.post("/api/auth/refresh").send().await;
    assert_eq!(refresh.status, StatusCode::OK, "{}", refresh.text());
    assert_eq!(refresh.json()["username"], "carol");
    let password = sso.password_login(&restored, "carol").await;
    assert_eq!(password.status, StatusCode::OK);
    assert_eq!(password.json()["mfaRequired"], true);
}

#[tokio::test]
async fn the_sso_window_bounds_refresh_and_new_app_authorizations() {
    let sso = Sso::ready().await;
    let alice = sso.user("alice").await;
    let old = sso.app.clock.now_utc() - Duration::hours(25);
    let stale_refresh = sso.app.state.jwt.refresh_token(&alice, None, Some(old));
    let refresher = sso.app.client();
    refresher.set_cookie("refresh_token", &stale_refresh);
    let refused = refresher.post("/api/auth/refresh").send().await;
    assert_eq!(refused.status, StatusCode::UNAUTHORIZED);
    assert_eq!(refused.json()["code"], "SSO_REVALIDATE");
    let stale_access = sso.app.state.jwt.access_token(&alice, Some(old));
    let expired = sso.authorize("picsou-ios", &stale_access).await;
    assert!(sso.path(&expired).starts_with("/login?redirect="));
    let fresh = sso
        .authorize("picsou-ios", &sso.access("alice").await)
        .await;
    assert!(
        fresh
            .header("location")
            .unwrap()
            .starts_with("picsou://callback?code=")
    );
    let local = sso
        .authorize("picsou-ios", &sso.app.state.jwt.access_token(&alice, None))
        .await;
    assert!(
        local
            .header("location")
            .unwrap()
            .starts_with("picsou://callback?code=")
    );
    let client = sso.register_client().await;
    let parked = sso.authorize(&client, &sso.access("alice").await).await;
    assert!(sso.path(&parked).starts_with("/consent?"));
    let consented = sso.consent(&client, &parked, &stale_access).await;
    assert!(
        consented
            .header("location")
            .unwrap()
            .starts_with(&format!("{DCR_REDIRECT}?code="))
    );
    let browser = sso.app.client();
    sso.login(&browser, "login", &[("rememberMe", "1")], "alice", true)
        .await;
    let persistent = browser.cookie("persistent_token").unwrap();
    let series = persistent.split(':').next().unwrap().to_owned();
    sqlx::query(
        "UPDATE persistent_session SET sso_authenticated_at = $2 WHERE series_id::text = $1",
    )
    .bind(&series)
    .bind(old)
    .execute(&sso.app.state.db)
    .await
    .unwrap();
    let remembered = sso.app.client();
    remembered.set_cookie("persistent_token", &persistent);
    let stale = remembered.post("/api/auth/refresh").send().await;
    assert_eq!(stale.status, StatusCode::UNAUTHORIZED);
    let revoked: Option<chrono::DateTime<Utc>> =
        sqlx::query_scalar("SELECT revoked_at FROM persistent_session WHERE series_id::text = $1")
            .bind(&series)
            .fetch_one(&sso.app.state.db)
            .await
            .unwrap();
    assert!(revoked.is_some());
}

#[tokio::test]
async fn reauth_needs_a_fresh_auth_time_and_sets_the_proof() {
    let sso = Sso::ready().await;
    let browser = sso.app.client();
    browser.set_cookie("access_token", &sso.access("alice").await);
    let old = sso.begin(&browser, "reauth", &[], "alice", true).await;
    assert_eq!(old.params["prompt"], "login");
    assert_eq!(old.params["max_age"], "0");
    let stale_code = sso.idp.issue_code(
        old.params.clone(),
        "alice",
        claims("alice", true),
        sso.app.clock.now_utc() - Duration::seconds(300),
    );
    let too_old = sso
        .finish(
            &browser,
            &Flow {
                params: old.params.clone(),
                code: stale_code,
            },
        )
        .await;
    assert_eq!(sso.path(&too_old), "/settings?ssoError=REAUTH_TOO_OLD");
    let fresh = sso.begin(&browser, "reauth", &[], "alice", true).await;
    let done = sso.finish(&browser, &fresh).await;
    assert_eq!(sso.path(&done), "/settings");
    assert!(browser.cookie("reauth_proof").is_some());
    let other = sso
        .begin(&browser, "reauth", &[], "someone-else", true)
        .await;
    let wrong = sso.finish(&browser, &other).await;
    assert_eq!(sso.path(&wrong), "/settings?ssoError=REAUTH_WRONG_ACCOUNT");
}

#[tokio::test]
async fn linking_needs_a_proof_and_binds_the_identity() {
    let sso = Sso::ready().await;
    let erin = sso.local_user("erin").await;
    let browser = sso.app.login("erin", LOCAL_PASSWORD).await;
    let without = sso.start(&browser, "link", &[]).await;
    assert_eq!(sso.path(&without), "/settings?ssoError=REAUTH_REQUIRED");
    let proof = browser
        .post("/api/auth/reauth")
        .json(&json!({"password": LOCAL_PASSWORD}))
        .send()
        .await;
    assert_eq!(proof.status, StatusCode::NO_CONTENT);
    let linked = sso.login(&browser, "link", &[], "erin-at-idp", false).await;
    assert_eq!(sso.path(&linked), "/settings?sso=linked");
    assert_eq!(sso.identities(erin).await, 1);
    let methods = browser.get("/api/auth/sign-in-methods").send().await.json();
    assert_eq!(methods["identities"][0]["currentIssuer"], true);
    assert_eq!(methods["ssoEnabled"], true);
    assert_eq!(methods["adminGroupManaged"], true);
    let taken = sso
        .login(&sso.app.client(), "login", &[], "erin-at-idp", false)
        .await;
    assert!(sso.path(&taken).starts_with("/login/sso-complete?"));
}

#[tokio::test]
async fn activation_binds_the_invitation_and_leaves_no_password() {
    let sso = Sso::ready().await;
    let invited = sso.local_user("invited").await;
    sqlx::query("UPDATE app_user SET is_activated = false, activation_token = 'invitation-token', activation_token_expires = $2 WHERE id = $1")
        .bind(invited)
        .bind(sso.app.clock.now_utc() + Duration::minutes(5))
        .execute(&sso.app.state.db)
        .await
        .unwrap();
    let browser = sso.app.client();
    let result = sso
        .login(
            &browser,
            "activate",
            &[("token", "invitation-token"), ("ack", "1")],
            "invited-sub",
            false,
        )
        .await;
    assert!(
        sso.path(&result).starts_with("/login/sso-complete?"),
        "{}",
        sso.path(&result)
    );
    assert!(browser.cookie("refresh_token").is_some());
    let user = sso.user("invited").await;
    assert!(user.activated);
    assert!(user.password_hash.is_empty());
    assert!(user.activation_token.is_none());
    let owner: i64 =
        sqlx::query_scalar("SELECT user_id FROM user_identity WHERE subject = 'invited-sub'")
            .fetch_one(&sso.app.state.db)
            .await
            .unwrap();
    assert_eq!(owner, invited);
}

#[tokio::test]
async fn the_handshake_is_post_only_single_use_and_same_site() {
    let sso = Sso::ready().await;
    sso.login(&sso.app.client(), "login", &[], "dave", true)
        .await;
    let client = sso.app.client();
    let flow = sso.begin(&client, "login", &[], "dave", true).await;
    let done = sso.finish(&client, &flow).await;
    assert!(sso.path(&done).starts_with("/login/sso-complete?"));
    let callback = format!("{}/login/sso/callback", sso.app.base_url);
    assert_eq!(flow.params["redirect_uri"], callback);
    assert_eq!(
        sso.idp.exchange(&flow.code).unwrap()["redirect_uri"],
        callback
    );
    let tokens = sso.idp.calls("/token");
    assert_eq!(sso.path(&client.get(START).send().await), "/login");
    assert_eq!(sso.path(&client.get(CALLBACK).send().await), "/login");
    let cross = client
        .post(START)
        .header("sec-fetch-site", "cross-site")
        .send()
        .await;
    assert_eq!(cross.status, StatusCode::FORBIDDEN);
    let cross = client
        .post(CALLBACK)
        .header("sec-fetch-site", "cross-site")
        .send()
        .await;
    assert_eq!(cross.status, StatusCode::FORBIDDEN);
    let replay = sso.finish(&client, &flow).await;
    assert_eq!(sso.path(&replay), "/login?ssoError=SESSION_EXPIRED");
    assert_eq!(sso.idp.calls("/token"), tokens);
    let browser = sso.app.client();
    let first = sso.begin(&browser, "login", &[], "dave", true).await;
    let second = sso.begin(&browser, "login", &[], "dave", true).await;
    let stale = sso.finish(&browser, &first).await;
    assert_eq!(sso.path(&stale), "/login?ssoError=SESSION_EXPIRED");
    let gone = sso.finish(&browser, &second).await;
    assert_eq!(sso.path(&gone), "/login?ssoError=SESSION_EXPIRED");
    assert!(sso.idp.exchange(&second.code).is_none());
}

#[tokio::test]
async fn idp_tokens_are_never_kept() {
    let sso = Sso::ready().await;
    let browser = sso.app.client();
    sso.login(&browser, "login", &[], "dave", true).await;
    assert_eq!(
        sso.count("SELECT count(*) FROM oauth2_authorization").await,
        0
    );
    let cookies: Vec<String> = ["access_token", "refresh_token"]
        .iter()
        .filter_map(|c| browser.cookie(c))
        .collect();
    assert_eq!(cookies.len(), 2);
    for cookie in cookies {
        assert!(!cookie.contains("idp-access"));
    }
}

#[tokio::test]
async fn a_subject_with_line_breaks_is_refused() {
    let sso = Sso::ready().await;
    let before = sso.count("SELECT count(*) FROM app_user").await;
    let result = sso
        .login(&sso.app.client(), "login", &[], "attacker\r\nforged", false)
        .await;
    assert_eq!(sso.path(&result), "/login?ssoError=NOT_LINKED");
    assert_eq!(sso.count("SELECT count(*) FROM app_user").await, before);
}

#[tokio::test]
async fn public_app_clients_get_access_tokens_without_refresh_tokens() {
    let sso = Sso::ready().await;
    let client = sso.register_client().await;
    for (client_id, redirect) in [
        ("picsou-ios", "picsou://callback"),
        (client.as_str(), DCR_REDIRECT),
    ] {
        let access = sso.access("alice").await;
        let mut authorization = sso.authorize(client_id, &access).await;
        if client_id != "picsou-ios" {
            authorization = sso.consent(client_id, &authorization, &access).await;
        }
        let location = authorization.header("location").unwrap().to_owned();
        assert!(
            location.starts_with(&format!("{redirect}?code=")),
            "{location}"
        );
        let code = query(&location)["code"].clone();
        let token = sso
            .app
            .client()
            .post("/oauth2/token")
            .body(
                "application/x-www-form-urlencoded",
                form(&[
                    ("grant_type", "authorization_code"),
                    ("code", &code),
                    ("client_id", client_id),
                    ("redirect_uri", redirect),
                    ("code_verifier", VERIFIER),
                ]),
            )
            .send()
            .await;
        assert_eq!(token.status, StatusCode::OK, "{}", token.text());
        let body = token.json();
        assert!(body["access_token"].as_str().is_some_and(|t| !t.is_empty()));
        assert_eq!(body.get("refresh_token"), None);
    }
}

#[tokio::test]
async fn a_password_reset_link_never_lets_an_idp_identity_take_over() {
    let sso = Sso::ready().await;
    let victim = sso.local_user("resetvictim").await;
    sqlx::query(
        "INSERT INTO user_mfa (user_id, enabled, totp_secret_enc) VALUES ($1, true, 'unused')",
    )
    .bind(victim)
    .execute(&sso.app.state.db)
    .await
    .unwrap();
    sqlx::query("UPDATE app_user SET activation_token = 'reset-token', activation_token_expires = $2 WHERE id = $1")
        .bind(victim)
        .bind(sso.app.clock.now_utc() + Duration::hours(1))
        .execute(&sso.app.state.db)
        .await
        .unwrap();
    let hash_before = sso.user("resetvictim").await.password_hash;
    let authorizations = sso.idp.calls("/token");
    let refused = sso
        .start(
            &sso.app.client(),
            "activate",
            &[("token", "reset-token"), ("ack", "1")],
        )
        .await;
    assert_eq!(
        sso.path(&refused),
        "/activate/reset-token?ssoError=ACTIVATION_INVALID"
    );
    assert_eq!(sso.idp.calls("/token"), authorizations);
    assert_eq!(sso.identities(victim).await, 0);
    assert_eq!(
        sso.count("SELECT count(*) FROM user_mfa WHERE enabled")
            .await,
        1
    );
    assert_eq!(sso.user("resetvictim").await.password_hash, hash_before);
}

async fn assert_id_token_rejected(fault: Fault) {
    let sso = Sso::ready().await;
    sso.login(&sso.app.client(), "login", &[], "dave", true)
        .await;
    let browser = sso.app.client();
    let flow = sso.begin(&browser, "login", &[], "dave", true).await;
    let userinfo = sso.idp.calls("/userinfo");
    sso.idp.fault_next_id_token(fault);
    let result = sso.finish(&browser, &flow).await;
    assert_eq!(sso.path(&result), "/login?ssoError=IDP_ERROR");
    assert!(
        result
            .set_cookies()
            .iter()
            .all(|c| !c.starts_with("access_token=")
                && !c.starts_with("refresh_token=")
                && !c.starts_with("persistent_token=")),
        "no session after a bad ID token"
    );
    assert_eq!(
        sso.idp.calls("/userinfo"),
        userinfo,
        "the ID token is checked before userinfo"
    );
}

#[tokio::test]
async fn an_id_token_with_a_bad_signature_is_rejected() {
    assert_id_token_rejected(Fault::BadSignature).await;
}

#[tokio::test]
async fn an_id_token_for_another_audience_is_rejected() {
    assert_id_token_rejected(Fault::WrongAudience).await;
}

#[tokio::test]
async fn an_id_token_from_another_issuer_is_rejected() {
    assert_id_token_rejected(Fault::WrongIssuer).await;
}

#[tokio::test]
async fn an_expired_id_token_is_rejected() {
    assert_id_token_rejected(Fault::Expired).await;
}

#[tokio::test]
async fn an_unreachable_idp_is_reported_and_backed_off() {
    let idp = wiremock::MockServer::start().await;
    let app = TestApp::builder()
        .env("SSO_ISSUER_URI", idp.uri())
        .env("SSO_CLIENT_ID", "picsou-test")
        .spawn()
        .await;
    let response = app
        .client()
        .post(START)
        .body("application/x-www-form-urlencoded", "intent=login")
        .send()
        .await;
    assert_eq!(
        response.header("location").unwrap(),
        format!("{}/login?ssoError=IDP_UNAVAILABLE", app.base_url)
    );
    let info = app.client().get("/api/auth/sso").send().await;
    assert_eq!(
        info.status,
        StatusCode::SERVICE_UNAVAILABLE,
        "setup is still pending"
    );
}

#[tokio::test]
async fn sso_routes_answer_like_the_api_while_sso_is_off() {
    let app = TestApp::with_admin().await;
    let anonymous = app.client().post(START).send().await;
    assert_eq!(anonymous.status, StatusCode::UNAUTHORIZED);
    let signed_in = app.login_admin().await.post(START).send().await;
    assert_eq!(signed_in.status, StatusCode::NOT_FOUND);
    let info = app.client().get("/api/auth/sso").send().await.json();
    assert_eq!(info, json!({"enabled": false, "providerName": "SSO"}));
}
