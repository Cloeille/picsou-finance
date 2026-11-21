use std::net::SocketAddr;
use std::time::Instant;

use picsou::config::{Config, Env};

#[tokio::main]
async fn main() {
    let _ = picsou::PROCESS_START.set(Instant::now());
    // `picsou migrate|verify|export-sqlite <output.db>`: database maintenance CLI subcommands.
    if let Some(command) = std::env::args().nth(1) {
        let args: Vec<String> = std::env::args().skip(2).collect();
        std::process::exit(
            picsou::db::release_cli::run_with_args(&command, &args, &Env::from_process()).await,
        );
    }
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
