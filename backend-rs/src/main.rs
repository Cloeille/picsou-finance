use std::net::SocketAddr;
use std::time::Instant;

use picsou::config::{Config, Env};

#[tokio::main]
async fn main() {
    let _ = picsou::PROCESS_START.set(Instant::now());
    let config = match Config::load(&Env::from_process()) {
        Ok(config) => config,
        Err(e) => {
            eprintln!("Application failed to start: {e}");
            std::process::exit(1);
        }
    };
    picsou::logging::init(config.log_file.as_deref());
    let addr = SocketAddr::new(config.server_address, config.server_port);
    let outcome = match tokio::net::TcpListener::bind(addr).await {
        Ok(listener) => picsou::serve(config, listener).await,
        Err(e) => Err(anyhow::anyhow!("cannot listen on {addr}: {e}")),
    };
    if let Err(e) = outcome {
        tracing::error!("Application failed to start: {e:#}");
        std::process::exit(1);
    }
}
