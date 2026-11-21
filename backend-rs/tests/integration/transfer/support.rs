//! Multipart bodies for the upload endpoints.

#![allow(dead_code)]

pub const BOUNDARY: &str = "----picsou-test-boundary";

pub enum Part<'a> {
    File {
        name: &'a str,
        filename: &'a str,
        content_type: &'a str,
        bytes: &'a [u8],
    },
    Text {
        name: &'a str,
        value: &'a str,
    },
}

/// The Content-Type header and body of a multipart/form-data request.
pub fn multipart(parts: &[Part<'_>]) -> (String, Vec<u8>) {
    let mut body = Vec::new();
    for part in parts {
        body.extend_from_slice(format!("--{BOUNDARY}\r\n").as_bytes());
        match part {
            Part::File {
                name,
                filename,
                content_type,
                bytes,
            } => {
                body.extend_from_slice(
                    format!(
                        "Content-Disposition: form-data; name=\"{name}\"; filename=\"{filename}\"\r\nContent-Type: {content_type}\r\n\r\n"
                    )
                    .as_bytes(),
                );
                body.extend_from_slice(bytes);
            }
            Part::Text { name, value } => {
                body.extend_from_slice(
                    format!("Content-Disposition: form-data; name=\"{name}\"\r\n\r\n{value}")
                        .as_bytes(),
                );
            }
        }
        body.extend_from_slice(b"\r\n");
    }
    body.extend_from_slice(format!("--{BOUNDARY}--\r\n").as_bytes());
    (format!("multipart/form-data; boundary={BOUNDARY}"), body)
}

/// A single file part named "file".
pub fn file(filename: &str, content_type: &str, bytes: &[u8]) -> (String, Vec<u8>) {
    multipart(&[Part::File {
        name: "file",
        filename,
        content_type,
        bytes,
    }])
}
