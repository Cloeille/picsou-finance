//! FinaryApiClient: the Clerk sign-in (password, optional TOTP second factor, session touch and
//! token) and the Finary data API, with Java's retry on 5xx and transport failures.

use std::error::Error as _;
use std::time::Duration;

use axum::http::StatusCode;
use reqwest::RequestBuilder;
use serde::de::DeserializeOwned;

use super::upstream::{
    ClerkSessionApiResponse, ClerkSignInApiResponse, ClerkTokenResponse, Envelope, FinaryAccount,
    Loan, Organization, Transaction,
};
use crate::AppState;
use crate::error::AppError;
use crate::ledger::sync_values::java_is_blank;
use crate::native_sidecars::common::{ReadBodyError, read_capped_text};

const CLERK_QUERY_PARAMS: &str = "?__clerk_api_version=2025-11-10&_clerk_js_version=5.125.4";
const USER_AGENT: &str = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.0.0 Safari/537.36";
const ORIGIN: &str = "https://app.finary.com";
const REFERER: &str = "https://app.finary.com/";
const ACCEPT_LANGUAGE: &str = "fr-FR,fr;q=0.9,en;q=0.8";
const TIMEOUT: Duration = Duration::from_secs(30);
const MAX_RESPONSE_BYTES: usize = 8 * 1024 * 1024;
const MAX_RETRIES: u32 = 3;
const UNREACHABLE: &str = "Unable to reach Finary. Please check your connection and try again.";

/// How a Finary call fails, as the Java exceptions sort it.
#[derive(Debug)]
pub enum Failure {
    /// SyncException: 422 with the message.
    Sync(String),
    /// TotpRequiredException: 403 with the message.
    Totp(String),
    /// FinaryServiceUnavailableException: 502 with a fixed message.
    Unavailable(String),
    App(AppError),
}

impl From<AppError> for Failure {
    fn from(error: AppError) -> Self {
        Self::App(error)
    }
}

impl From<sqlx::Error> for Failure {
    fn from(error: sqlx::Error) -> Self {
        Self::App(error.into())
    }
}

impl From<Failure> for AppError {
    fn from(failure: Failure) -> Self {
        match failure {
            Failure::Sync(message) => AppError::status(StatusCode::UNPROCESSABLE_ENTITY, message),
            Failure::Totp(message) => {
                tracing::info!("TOTP required: {message}");
                AppError::Forbidden(message)
            }
            Failure::Unavailable(message) => {
                tracing::warn!("Finary/Clerk service unavailable: {message}");
                AppError::status(
                    StatusCode::BAD_GATEWAY,
                    "Finary service is temporarily unavailable. Please try again later.",
                )
            }
            Failure::App(error) => error,
        }
    }
}

/// One step's failure before the caller names the operation: a SyncException passes through, an
/// IOException becomes either an outage or a SyncException prefixed with the operation.
enum Step {
    Sync(String),
    Io(String),
}

impl Step {
    fn into_failure(self, prefix: &str) -> Failure {
        match self {
            Step::Sync(message) => Failure::Sync(message),
            Step::Io(message) if is_network_error(&message) => {
                Failure::Unavailable(UNREACHABLE.to_owned())
            }
            Step::Io(message) => Failure::Sync(format!("{prefix}{message}")),
        }
    }
}

fn is_network_error(message: &str) -> bool {
    let lower = message.to_lowercase();
    [
        "timed out",
        "connection refused",
        "unreachable",
        "connect failed",
        "connection reset",
        "ssl",
        "unknownhost",
        "no route to host",
    ]
    .iter()
    .any(|needle| lower.contains(needle))
}

fn parse<T: DeserializeOwned>(body: &str) -> Result<T, Step> {
    serde_json::from_str(body).map_err(|_| Step::Io("unreadable response body".to_owned()))
}

/// The organization and membership every portfolio path is scoped to.
#[derive(Debug, Clone)]
pub struct OrgContext {
    pub org_id: String,
    pub membership_id: String,
}

pub struct FinaryClient {
    clerk: reqwest::Client,
    api: reqwest::Client,
    clerk_base: String,
    api_base: String,
}

impl FinaryClient {
    /// A client with a fresh Clerk cookie jar, as each Java sign-in builds its own.
    pub fn new(state: &AppState) -> Result<Self, Failure> {
        let build = |cookies: bool| {
            reqwest::Client::builder()
                .cookie_store(cookies)
                .connect_timeout(TIMEOUT)
                .timeout(TIMEOUT)
                .build()
                .map_err(|e| Failure::App(AppError::internal(e)))
        };
        Ok(Self {
            clerk: build(true)?,
            api: build(false)?,
            clerk_base: state
                .config
                .sync_url("finary-clerk")
                .trim_end_matches('/')
                .to_owned(),
            api_base: state
                .config
                .sync_url("finary-api")
                .trim_end_matches('/')
                .to_owned(),
        })
    }

    /// checkTotpRequired: the sign-in id when Clerk asks for a second factor, None otherwise.
    pub async fn check_totp_required(
        &self,
        email: &str,
        password: &str,
    ) -> Result<Option<String>, Failure> {
        self.sign_in_status(email, password)
            .await
            .map(|(id, status, _)| {
                if status == "needs_second_factor" {
                    tracing::info!("Finary auth requires TOTP");
                    id
                } else {
                    None
                }
            })
            .map_err(|step| step.into_failure("Finary auth check failed: "))
    }

    /// Steps 1 to 3 of the sign-in: (id, status, createdSessionId); a sign-in without a
    /// status fails.
    async fn sign_in_status(
        &self,
        email: &str,
        password: &str,
    ) -> Result<(Option<String>, String, Option<String>), Step> {
        self.clerk_get("/v1/environment").await?;
        self.clerk_get("/v1/client").await?;
        let body = format!("identifier={}&password={}", form(email), form(password));
        let response: ClerkSignInApiResponse =
            parse(&self.clerk_post("/v1/client/sign_ins", body).await?)?;
        let sign_in = response.response.unwrap_or_default();
        let Some(status) = sign_in.status else {
            tracing::warn!("Finary sign-in returned no status field in the Clerk response");
            return Err(Step::Sync(
                "Finary sign-in failed. Please check your credentials and try again.".to_owned(),
            ));
        };
        Ok((sign_in.id, status, sign_in.created_session_id))
    }

    /// authenticate: the Clerk JWT for the Finary API.
    pub async fn authenticate(
        &self,
        email: &str,
        password: &str,
        totp: Option<&str>,
    ) -> Result<String, Failure> {
        const PREFIX: &str = "Clerk authentication failed: ";
        let (sign_in_id, status, created_session_id) = self
            .sign_in_status(email, password)
            .await
            .map_err(|s| s.into_failure(PREFIX))?;
        let session_id = if status == "needs_second_factor" {
            let Some(code) = totp.filter(|t| !java_is_blank(t)) else {
                return Err(Failure::Totp(
                    "TOTP required: account has 2FA enabled".to_owned(),
                ));
            };
            let path = format!(
                "/v1/client/sign_ins/{}/attempt_second_factor",
                sign_in_id.as_deref().unwrap_or("null")
            );
            let body = self
                .clerk_post(&path, format!("strategy=totp&code={}", form(code)))
                .await
                .map_err(|s| s.into_failure(PREFIX))?;
            parse::<ClerkSessionApiResponse>(&body)
                .map_err(|s| s.into_failure(PREFIX))?
                .first_session_id()
        } else {
            created_session_id
        };
        let Some(session_id) = session_id else {
            return Err(Failure::Sync(
                "Could not obtain Clerk session ID".to_owned(),
            ));
        };
        self.clerk_post(
            &format!("/v1/client/sessions/{session_id}/touch"),
            "active_organization_id=".to_owned(),
        )
        .await
        .map_err(|s| s.into_failure(PREFIX))?;
        let body = self
            .clerk_post(
                &format!("/v1/client/sessions/{session_id}/tokens"),
                "organization_id=".to_owned(),
            )
            .await
            .map_err(|s| s.into_failure(PREFIX))?;
        let token: ClerkTokenResponse = parse(&body).map_err(|s| s.into_failure(PREFIX))?;
        match token.jwt.filter(|jwt| !java_is_blank(jwt)) {
            Some(jwt) => {
                tracing::info!("Clerk authentication successful");
                Ok(jwt)
            }
            None => Err(Failure::Sync("Clerk did not return a JWT token".to_owned())),
        }
    }

    /// fetchOrganizationContext: the first organization and the member with a full name.
    pub async fn organization_context(&self, jwt: &str) -> Result<OrgContext, Failure> {
        let envelope: Envelope<Vec<Option<Organization>>> = self
            .finary_json(jwt, "/users/me/organizations")
            .await
            .map_err(|s| s.into_failure("Failed to fetch organization context: "))?;
        let Some(org) = envelope.result.unwrap_or_default().into_iter().next() else {
            return Err(Failure::Sync("No organizations found for user".to_owned()));
        };
        let org = org
            .ok_or_else(|| Failure::Sync("User membership not found in organization".to_owned()))?;
        for member in org.members.into_iter().flatten().flatten() {
            if member.user.as_ref().is_some_and(|u| u.fullname.is_some()) {
                tracing::info!(
                    "Found organization - memberType: {}",
                    member.member_type.as_deref().unwrap_or("null")
                );
                return Ok(OrgContext {
                    org_id: org.id.clone().unwrap_or_else(|| "null".to_owned()),
                    membership_id: member.id.unwrap_or_else(|| "null".to_owned()),
                });
            }
        }
        Err(Failure::Sync(
            "User membership not found in organization".to_owned(),
        ))
    }

    pub async fn category_accounts(
        &self,
        jwt: &str,
        ctx: &OrgContext,
        category: &str,
    ) -> Result<Vec<FinaryAccount>, Failure> {
        let path = format!(
            "/organizations/{}/memberships/{}/portfolio/{category}/accounts",
            ctx.org_id, ctx.membership_id
        );
        let envelope: Envelope<Vec<FinaryAccount>> =
            self.finary_json(jwt, &path).await.map_err(|s| {
                s.into_failure(&format!(
                    "Failed to fetch accounts for category {category}: "
                ))
            })?;
        Ok(envelope.result.unwrap_or_default())
    }

    pub async fn loans(&self, jwt: &str) -> Result<Vec<Loan>, Failure> {
        let envelope: Envelope<Vec<Loan>> = self
            .finary_json(jwt, "/users/me/loans")
            .await
            .map_err(|s| s.into_failure("Failed to fetch loans: "))?;
        Ok(envelope.result.unwrap_or_default())
    }

    pub async fn category_transactions(
        &self,
        jwt: &str,
        ctx: &OrgContext,
        category: &str,
        page: u32,
        per_page: usize,
    ) -> Result<Vec<Transaction>, Failure> {
        let path = format!(
            "/organizations/{}/memberships/{}/portfolio/{category}/transactions?page={page}&per_page={per_page}",
            ctx.org_id, ctx.membership_id
        );
        let envelope: Envelope<Vec<Transaction>> =
            self.finary_json(jwt, &path).await.map_err(|s| {
                s.into_failure(&format!(
                    "Failed to fetch transactions for category {category}: "
                ))
            })?;
        Ok(envelope.result.unwrap_or_default())
    }

    fn clerk_request(&self, request: RequestBuilder) -> RequestBuilder {
        request
            .header("User-Agent", USER_AGENT)
            .header("Origin", ORIGIN)
            .header("Referer", REFERER)
            .header("Accept-Language", ACCEPT_LANGUAGE)
    }

    async fn clerk_get(&self, path: &str) -> Result<String, Step> {
        let url = format!("{}{path}{CLERK_QUERY_PARAMS}", self.clerk_base);
        clerk_outcome(send_with_retry(|| self.clerk_request(self.clerk.get(&url))).await?)
    }

    async fn clerk_post(&self, path: &str, form_body: String) -> Result<String, Step> {
        let url = format!("{}{path}{CLERK_QUERY_PARAMS}", self.clerk_base);
        clerk_outcome(
            send_with_retry(|| {
                self.clerk_request(self.clerk.post(&url))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .body(form_body.clone())
            })
            .await?,
        )
    }

    async fn finary_json<T: DeserializeOwned>(&self, jwt: &str, path: &str) -> Result<T, Step> {
        let url = format!("{}{path}", self.api_base);
        let (status, body) = send_with_retry(|| {
            self.api
                .get(&url)
                .header("Authorization", format!("Bearer {jwt}"))
                .header("Origin", ORIGIN)
                .header("Referer", REFERER)
                .header("x-client-api-version", "2")
                .header("x-finary-client-id", "webapp")
                .header("User-Agent", USER_AGENT)
                .header("Accept-Language", ACCEPT_LANGUAGE)
        })
        .await?;
        if status >= 400 {
            tracing::error!("Finary API error {status}");
            return Err(Step::Io(format!("HTTP {status}")));
        }
        parse(&body)
    }
}

/// Clerk's answer: 401 is a credentials failure, any other error status an IOException. The
/// body is not carried into the message: it can hold auth material.
fn clerk_outcome((status, body): (u16, String)) -> Result<String, Step> {
    if status == 401 {
        return Err(Step::Sync(
            "Invalid Finary credentials. Please check your email and password.".to_owned(),
        ));
    }
    if status >= 400 {
        return Err(Step::Io(format!("HTTP {status}")));
    }
    Ok(body)
}

/// URLEncoder.encode(value, UTF_8): form encoding, space as '+'.
fn form(value: &str) -> String {
    url::form_urlencoded::byte_serialize(value.as_bytes()).collect()
}

/// sendWithRetry: three attempts, 1 s then 2 s apart, on a 5xx or a transport failure.
async fn send_with_retry(request: impl Fn() -> RequestBuilder) -> Result<(u16, String), Step> {
    let mut last = String::new();
    for attempt in 0..MAX_RETRIES {
        let outcome: Result<(), String> = match request().send().await {
            Ok(response) => {
                let status = response.status().as_u16();
                if status >= 500 {
                    tracing::warn!(
                        "Finary/Clerk returned HTTP {status} (attempt {}/{MAX_RETRIES})",
                        attempt + 1
                    );
                    Err(format!("HTTP {status}"))
                } else {
                    match read_capped_text(response, MAX_RESPONSE_BYTES).await {
                        Ok(body) => return Ok((status, body)),
                        Err(ReadBodyError::TooLarge { .. }) => {
                            return Err(Step::Io("unreadable response body".to_owned()));
                        }
                        Err(ReadBodyError::Transport(error)) => Err(describe(&error)),
                    }
                }
            }
            Err(error) => {
                let message = describe(&error);
                tracing::warn!(
                    "Request failed (attempt {}/{MAX_RETRIES}): {message}",
                    attempt + 1
                );
                Err(message)
            }
        };
        if let Err(message) = outcome {
            last = message;
        }
        if attempt < MAX_RETRIES - 1 {
            tokio::time::sleep(Duration::from_secs(1 << attempt)).await;
        }
    }
    Err(Step::Io(last))
}

/// The IOException message Java would carry for a transport failure. A refused CONNECT through
/// the outbound proxy reads "Tunnel failed": the JDK adds the proxy's status, which reqwest
/// does not expose.
fn describe(error: &reqwest::Error) -> String {
    let mut chain = Vec::new();
    let mut source = error.source();
    while let Some(cause) = source {
        chain.push(cause.to_string());
        source = cause.source();
    }
    if chain.iter().any(|c| c == "tunnel error: unsuccessful") {
        return "Tunnel failed".to_owned();
    }
    if error.is_timeout() {
        return "request timed out".to_owned();
    }
    let root = chain
        .last()
        .cloned()
        .unwrap_or_else(|| "request failed".to_owned());
    if error.is_connect() {
        return format!("connect failed: {root}");
    }
    root
}
