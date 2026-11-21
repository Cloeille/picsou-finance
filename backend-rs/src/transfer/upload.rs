//! Multipart uploads as Spring's MultipartFile and @RequestParam see them: 415 for a body that
//! is not multipart, 413 past the 10 MB of spring.servlet.multipart, 400 for a missing part.

use std::collections::HashMap;

use axum::extract::{FromRequest, Multipart, Request};
use bytes::Bytes;
use http::{StatusCode, header};

use crate::error::AppError;

/// spring.servlet.multipart.max-file-size and max-request-size.
pub const MAX_UPLOAD_BYTES: usize = 10 * 1024 * 1024;

#[derive(Debug, Clone)]
pub struct Upload {
    pub filename: Option<String>,
    pub content_type: Option<String>,
    pub bytes: Bytes,
}

impl Upload {
    /// MultipartFile.isEmpty.
    pub fn is_empty(&self) -> bool {
        self.bytes.is_empty()
    }
}

/// Every part of a multipart/form-data body plus the query parameters.
#[derive(Debug, Default)]
pub struct MultipartForm {
    files: HashMap<String, Upload>,
    params: HashMap<String, String>,
}

impl MultipartForm {
    /// The file part, or Spring's MissingServletRequestPartException.
    pub fn file(&self, name: &str) -> Result<&Upload, AppError> {
        self.files
            .get(name)
            .ok_or_else(|| AppError::BadRequest(format!("Required part '{name}' is not present.")))
    }

    /// An optional @RequestParam: the query string first, then a text part.
    pub fn param(&self, name: &str) -> Option<&str> {
        self.params.get(name).map(String::as_str)
    }
}

fn too_large() -> AppError {
    AppError::status(
        StatusCode::PAYLOAD_TOO_LARGE,
        "Maximum upload size exceeded",
    )
}

impl<S: Send + Sync> FromRequest<S> for MultipartForm {
    type Rejection = AppError;

    async fn from_request(request: Request, state: &S) -> Result<Self, Self::Rejection> {
        let content_type = request
            .headers()
            .get(header::CONTENT_TYPE)
            .and_then(|v| v.to_str().ok())
            .unwrap_or("application/octet-stream")
            .to_owned();
        if !content_type
            .to_ascii_lowercase()
            .starts_with("multipart/form-data")
        {
            return Err(AppError::status(
                StatusCode::UNSUPPORTED_MEDIA_TYPE,
                format!("Content-Type '{content_type}' is not supported."),
            ));
        }
        let mut form = Self::default();
        if let Some(query) = request.uri().query() {
            for (k, v) in url::form_urlencoded::parse(query.as_bytes()) {
                form.params.entry(k.into_owned()).or_insert(v.into_owned());
            }
        }
        if request
            .headers()
            .get(header::CONTENT_LENGTH)
            .and_then(|v| v.to_str().ok())
            .and_then(|v| v.parse::<usize>().ok())
            .is_some_and(|len| len > MAX_UPLOAD_BYTES)
        {
            return Err(too_large());
        }
        let mut multipart = Multipart::from_request(request, state).await.map_err(|_| {
            AppError::BadRequest("Failed to parse multipart servlet request".into())
        })?;
        let mut total = 0usize;
        loop {
            let field = match multipart.next_field().await {
                Ok(Some(field)) => field,
                Ok(None) => break,
                Err(e) if e.status() == StatusCode::PAYLOAD_TOO_LARGE => return Err(too_large()),
                Err(_) => {
                    return Err(AppError::BadRequest(
                        "Failed to parse multipart servlet request".into(),
                    ));
                }
            };
            let name = field.name().unwrap_or_default().to_owned();
            let filename = field.file_name().map(str::to_owned);
            let part_type = field.content_type().map(str::to_owned);
            let bytes = field.bytes().await.map_err(|e| {
                if e.status() == StatusCode::PAYLOAD_TOO_LARGE {
                    too_large()
                } else {
                    AppError::BadRequest("Failed to parse multipart servlet request".into())
                }
            })?;
            total += bytes.len();
            if total > MAX_UPLOAD_BYTES {
                return Err(too_large());
            }
            match filename {
                Some(filename) => {
                    form.files.entry(name).or_insert(Upload {
                        filename: Some(filename),
                        content_type: part_type,
                        bytes,
                    });
                }
                None => {
                    form.params
                        .entry(name)
                        .or_insert_with(|| String::from_utf8_lossy(&bytes).into_owned());
                }
            }
        }
        Ok(form)
    }
}
