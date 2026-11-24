//! Nasdaq dividend rows: dates, amounts with dollar signs, N/A, error codes.

use picsou::market::nasdaq::{fetch_dividends, parse};
use std::time::Duration;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpListener;

#[test]
fn rows_parse_with_their_quirks() {
    let body = r#"{"data":{"dividends":{"rows":[
        {"exOrEffDate":"02/14/2026","paymentDate":"03/12/2026","recordDate":"02/14/2026","declarationDate":"01/29/2026","amount":"$0.26","currency":"USD"},
        {"exOrEffDate":"N/A","amount":"$0.25"},
        {"exOrEffDate":"11/08/2025","paymentDate":"N/A","amount":"$1,000.5"},
        {"exOrEffDate":"08/08/2025","amount":"N/A"}
    ]}},"status":{"rCode":200}}"#;
    let rows = parse(body).unwrap();
    assert_eq!(rows.len(), 2);
    assert_eq!(rows[0].ex_date.to_string(), "2026-02-14");
    assert_eq!(rows[0].payment_date.unwrap().to_string(), "2026-03-12");
    assert_eq!(rows[0].amount.to_string(), "0.26");
    assert_eq!(rows[0].currency.as_deref(), Some("USD"));
    assert_eq!(rows[1].amount.to_string(), "1000.5");
    assert!(rows[1].payment_date.is_none());
}

#[test]
fn an_error_code_or_a_broken_body_is_an_error_and_no_rows_is_empty() {
    assert!(parse(r#"{"status":{"rCode":400}}"#).is_err());
    assert!(parse("<html>").is_err());
    assert!(parse("  ").is_err());
    assert!(
        parse(r#"{"data":{"dividends":{"rows":null}},"status":{"rCode":200}}"#)
            .unwrap()
            .is_empty()
    );
}

#[tokio::test]
async fn fetch_dividends_aborts_streaming_body_past_size_cap() {
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let addr = listener.local_addr().unwrap();
    let server = tokio::spawn(async move {
        let (mut socket, _) = listener.accept().await.unwrap();
        let mut req = [0u8; 2048];
        let _ = socket.read(&mut req).await;
        let _ = socket
            .write_all(
                b"HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nContent-Type: application/json\r\n\r\n",
            )
            .await;
        let payload = vec![b' '; 64 * 1024];
        let header = format!("{:x}\r\n", payload.len());
        // Send 640 KiB (> 512 KiB cap) and leave the stream open without a terminating 0 chunk.
        for _ in 0..10 {
            if socket.write_all(header.as_bytes()).await.is_err()
                || socket.write_all(&payload).await.is_err()
                || socket.write_all(b"\r\n").await.is_err()
            {
                return;
            }
        }
        let _ = socket.flush().await;
        tokio::time::sleep(Duration::from_secs(30)).await;
    });

    let http = reqwest::Client::new();
    let result = tokio::time::timeout(
        Duration::from_secs(2),
        fetch_dividends(&http, &format!("http://{addr}"), "AAPL"),
    )
    .await
    .expect("client must abort once the cap is exceeded instead of waiting for EOF");
    assert_eq!(result, Err("nasdaq body too large".to_owned()));
    server.abort();
}
