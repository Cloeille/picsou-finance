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

/// An RFC 9110 quality value ("0.8") in thousandths, without floating point.
fn quality_millis(q: &str) -> u32 {
    let (whole, fraction) = q.trim().split_once('.').unwrap_or((q.trim(), ""));
    let digits: String = fraction.chars().chain("000".chars()).take(3).collect();
    whole.parse::<u32>().unwrap_or(0) * 1000 + digits.parse::<u32>().unwrap_or(0)
}

/// The language of Hibernate Validator's default messages, from Accept-Language as Spring's
/// LocaleResolver picks it. Messages a DTO sets itself are never translated.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub enum Lang {
    #[default]
    English,
    French,
}

impl Lang {
    /// The preferred language: the highest q value, the first one on a tie.
    pub fn from_accept_language(header: Option<&str>) -> Self {
        let Some(header) = header else {
            return Self::English;
        };
        let mut best: Option<(&str, u32)> = None;
        for part in header.split(',') {
            let mut pieces = part.split(';');
            let tag = pieces.next().unwrap_or_default().trim();
            let q = pieces
                .find_map(|p| p.trim().strip_prefix("q="))
                .map(quality_millis)
                .unwrap_or(1000);
            if !tag.is_empty() && best.is_none_or(|(_, bq)| q > bq) {
                best = Some((tag, q));
            }
        }
        match best {
            Some((tag, _))
                if tag
                    .split(['-', '_'])
                    .next()
                    .is_some_and(|l| l.eq_ignore_ascii_case("fr")) =>
            {
                Self::French
            }
            _ => Self::English,
        }
    }
}

/// Collects field errors; the first error of a field wins, as the Java handler merges them.
#[derive(Debug, Default)]
pub struct Errors {
    errors: FieldErrors,
    lang: Lang,
}

impl Errors {
    pub fn new(lang: Lang) -> Self {
        Self {
            errors: FieldErrors::new(),
            lang,
        }
    }

    /// A Hibernate Validator default message in the request language.
    pub fn default_message(&self, english: &str, french: &str) -> String {
        match self.lang {
            Lang::English => english.to_owned(),
            Lang::French => french.to_owned(),
        }
    }

    pub fn add(&mut self, field: &str, message: impl Into<String>) {
        self.errors
            .entry(field.to_owned())
            .or_insert_with(|| message.into());
    }

    /// @NotEmpty on a collection.
    pub fn not_empty<T>(&mut self, field: &str, value: Option<&[T]>) -> &mut Self {
        if value.is_none_or(<[T]>::is_empty) {
            let message = self.default_message("must not be empty", "ne doit pas être vide");
            self.add(field, message);
        }
        self
    }

    /// The language default messages are written in, for a constraint without a helper here.
    pub fn lang(&self) -> Lang {
        self.lang
    }

    pub fn has(&self, field: &str) -> bool {
        self.errors.contains_key(field)
    }

    /// @NotBlank
    pub fn not_blank(&mut self, field: &str, value: Option<&str>) -> &mut Self {
        if value.is_none_or(|v| v.trim().is_empty()) {
            let message = self.default_message("must not be blank", "ne doit pas être vide");
            self.add(field, message);
        }
        self
    }

    /// @NotNull
    pub fn not_null<T>(&mut self, field: &str, value: Option<&T>) -> &mut Self {
        if value.is_none() {
            let message = self.default_message("must not be null", "ne doit pas être nul");
            self.add(field, message);
        }
        self
    }

    /// @Size on a string, counted in UTF-16 units like Java; null is valid.
    pub fn size(&mut self, field: &str, value: Option<&str>, min: usize, max: usize) -> &mut Self {
        if let Some(v) = value {
            let len = v.encode_utf16().count();
            if len < min || len > max {
                let message = self.default_message(
                    &format!("size must be between {min} and {max}"),
                    &format!("la taille doit être comprise entre {min} et {max}"),
                );
                self.add(field, message);
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

    /// @Min on an integer; null is valid.
    pub fn min(&mut self, field: &str, value: Option<i64>, min: i64) -> &mut Self {
        if value.is_some_and(|v| v < min) {
            let message = self.default_message(
                &format!("must be greater than or equal to {min}"),
                &format!("doit être supérieur ou égal à {min}"),
            );
            self.add(field, message);
        }
        self
    }

    /// @Max on an integer; null is valid.
    pub fn max(&mut self, field: &str, value: Option<i64>, max: i64) -> &mut Self {
        if value.is_some_and(|v| v > max) {
            let message = self.default_message(
                &format!("must be less than or equal to {max}"),
                &format!("doit être inférieur ou égal à {max}"),
            );
            self.add(field, message);
        }
        self
    }

    /// @PositiveOrZero on a number; null is valid.
    pub fn positive_or_zero(
        &mut self,
        field: &str,
        value: Option<&rust_decimal::Decimal>,
    ) -> &mut Self {
        if value.is_some_and(|v| v.is_sign_negative() && !v.is_zero()) {
            let message = self.default_message(
                "must be greater than or equal to 0",
                "doit être supérieur ou égal à 0",
            );
            self.add(field, message);
        }
        self
    }

    /// @Pattern without a message: Hibernate Validator's default, quoting `regexp` as declared.
    pub fn pattern_default(
        &mut self,
        field: &str,
        value: Option<&str>,
        regex: &regex::Regex,
        regexp: &str,
    ) -> &mut Self {
        if let Some(v) = value
            && !regex.is_match(v)
        {
            let message = self.default_message(
                &format!("must match \"{regexp}\""),
                &format!("doit correspondre à \"{regexp}\""),
            );
            self.add(field, message);
        }
        self
    }

    /// @AssertTrue
    pub fn assert_true(&mut self, field: &str, value: bool) -> &mut Self {
        if !value {
            let message = self.default_message("must be true", "doit être vrai");
            self.add(field, message);
        }
        self
    }

    pub fn into_result(self) -> Result<(), AppError> {
        if self.errors.is_empty() {
            Ok(())
        } else {
            Err(AppError::Validation(self.errors))
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
    let raw_content_type = request
        .headers()
        .get(header::CONTENT_TYPE)
        .and_then(|v| v.to_str().ok())
        .map(str::to_owned);
    let content_type = raw_content_type
        .as_deref()
        .unwrap_or("application/octet-stream");
    if !is_json(content_type) {
        // Spring Boot's CharacterEncodingFilter sets UTF-8 on ServletRequest so
        // ServletServerHttpRequest attaches `;charset=UTF-8` to an explicit Content-Type without one.
        let reported = match raw_content_type {
            Some(ct) if !ct.to_ascii_lowercase().contains("charset") => {
                format!("{ct};charset=UTF-8")
            }
            Some(ct) => ct,
            None => "application/octet-stream".to_owned(),
        };
        return Err(AppError::status(
            StatusCode::UNSUPPORTED_MEDIA_TYPE,
            format!("Content-Type '{reported}' is not supported."),
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
        let lang = Lang::from_accept_language(
            request
                .headers()
                .get(header::ACCEPT_LANGUAGE)
                .and_then(|v| v.to_str().ok()),
        );
        let value: T = read_json(request, state).await?;
        let mut errors = Errors::new(lang);
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
