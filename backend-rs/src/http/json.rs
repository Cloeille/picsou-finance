//! Request-body and path extractors that fail like Spring MVC: 415 for a non-JSON body,
//! 400 "Failed to read request" for unreadable JSON, 422 with the errors map for bean
//! validation, 400 "Failed to convert ..." for a path variable of the wrong type.

use axum::extract::rejection::PathRejection;
use axum::extract::{FromRequest, FromRequestParts, Request};
use axum::http::header;
use axum::http::request::Parts;
use bytes::Bytes;
use http::StatusCode;
use serde::de::DeserializeOwned;

use crate::error::{AppError, FieldErrors};

/// Bean validation of a request body. Messages are Hibernate Validator's English defaults
/// unless the Java DTO sets its own.
pub trait Validate {
    fn validate(&self, errors: &mut Errors);
}

/// Collects field errors; the first error of a field wins, as the Java handler merges them.
#[derive(Debug, Default)]
pub struct Errors(FieldErrors);

impl Errors {
    pub fn add(&mut self, field: &str, message: impl Into<String>) {
        self.0
            .entry(field.to_owned())
            .or_insert_with(|| message.into());
    }

    pub fn has(&self, field: &str) -> bool {
        self.0.contains_key(field)
    }

    /// @NotBlank
    pub fn not_blank(&mut self, field: &str, value: Option<&str>) -> &mut Self {
        if value.is_none_or(|v| v.trim().is_empty()) {
            self.add(field, "must not be blank");
        }
        self
    }

    /// @NotNull
    pub fn not_null<T>(&mut self, field: &str, value: Option<&T>) -> &mut Self {
        if value.is_none() {
            self.add(field, "must not be null");
        }
        self
    }

    /// @Size on a string, counted in UTF-16 units like Java; null is valid.
    pub fn size(&mut self, field: &str, value: Option<&str>, min: usize, max: usize) -> &mut Self {
        if let Some(v) = value {
            let len = v.encode_utf16().count();
            if len < min || len > max {
                self.add(field, format!("size must be between {min} and {max}"));
            }
        }
        self
    }

    /// @Pattern with a message; null is valid. The regex must match the whole value.
    pub fn pattern(
        &mut self,
        field: &str,
        value: Option<&str>,
        regex: &regex::Regex,
        message: &str,
    ) -> &mut Self {
        if let Some(v) = value
            && !regex.is_match(v)
        {
            self.add(field, message);
        }
        self
    }

    /// @AssertTrue
    pub fn assert_true(&mut self, field: &str, value: bool) -> &mut Self {
        if !value {
            self.add(field, "must be true");
        }
        self
    }

    pub fn into_result(self) -> Result<(), AppError> {
        if self.0.is_empty() {
            Ok(())
        } else {
            Err(AppError::Validation(self.0))
        }
    }
}

/// A JSON request body, parsed with Jackson's leniency (unknown fields ignored) and validated.
#[derive(Debug, Clone)]
pub struct Json<T>(pub T);

fn is_json(content_type: &str) -> bool {
    let essence = content_type
        .split(';')
        .next()
        .unwrap_or_default()
        .trim()
        .to_ascii_lowercase();
    essence == "application/json"
        || (essence.starts_with("application/") && essence.ends_with("+json"))
}

/// Parses a JSON body as a @RequestBody would, without validation.
pub async fn read_json<T: DeserializeOwned, S: Send + Sync>(
    request: Request,
    state: &S,
) -> Result<T, AppError> {
    let content_type = request
        .headers()
        .get(header::CONTENT_TYPE)
        .and_then(|v| v.to_str().ok())
        .unwrap_or("application/octet-stream")
        .to_owned();
    if !is_json(&content_type) {
        return Err(AppError::status(
            StatusCode::UNSUPPORTED_MEDIA_TYPE,
            format!("Content-Type '{content_type}' is not supported."),
        ));
    }
    let body = Bytes::from_request(request, state)
        .await
        .map_err(|_| AppError::BadRequest("Failed to read request".into()))?;
    serde_json::from_slice(&body).map_err(|_| AppError::BadRequest("Failed to read request".into()))
}

impl<T, S> FromRequest<S> for Json<T>
where
    T: DeserializeOwned + Validate,
    S: Send + Sync,
{
    type Rejection = AppError;

    async fn from_request(request: Request, state: &S) -> Result<Self, Self::Rejection> {
        let value: T = read_json(request, state).await?;
        let mut errors = Errors::default();
        value.validate(&mut errors);
        errors.into_result()?;
        Ok(Self(value))
    }
}

/// A path variable, rejected like Spring's MethodArgumentTypeMismatchException.
#[derive(Debug, Clone)]
pub struct Path<T>(pub T);

impl<T, S> FromRequestParts<S> for Path<T>
where
    T: DeserializeOwned + Send,
    S: Send + Sync,
{
    type Rejection = AppError;

    async fn from_request_parts(parts: &mut Parts, state: &S) -> Result<Self, Self::Rejection> {
        match axum::extract::Path::<T>::from_request_parts(parts, state).await {
            Ok(axum::extract::Path(value)) => Ok(Self(value)),
            Err(PathRejection::FailedToDeserializePathParams(e)) => {
                use axum::extract::path::ErrorKind;
                match e.into_kind() {
                    ErrorKind::ParseErrorAtKey { key, value, .. } => Err(AppError::BadRequest(
                        format!("Failed to convert '{key}' with value: '{value}'"),
                    )),
                    ErrorKind::ParseError { value, .. } => {
                        // A single path variable: name it from the matched route.
                        let key = axum::extract::RawPathParams::from_request_parts(parts, state)
                            .await
                            .ok()
                            .and_then(|params| {
                                params
                                    .iter()
                                    .find(|(_, v)| *v == value)
                                    .map(|(k, _)| k.to_owned())
                            });
                        Err(AppError::BadRequest(match key {
                            Some(key) => format!("Failed to convert '{key}' with value: '{value}'"),
                            None => format!("Failed to convert value: '{value}'"),
                        }))
                    }
                    _ => Err(AppError::BadRequest("Failed to read request".into())),
                }
            }
            Err(other) => Err(AppError::internal(other)),
        }
    }
}

/// Jackson maps a JSON null or a missing field of a primitive boolean to false.
pub fn null_as_false<'de, D: serde::Deserializer<'de>>(deserializer: D) -> Result<bool, D::Error> {
    use serde::Deserialize;
    Ok(Option::<bool>::deserialize(deserializer)?.unwrap_or(false))
}
