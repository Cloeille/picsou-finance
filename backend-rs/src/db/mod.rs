//! The connection pool and the Flyway-compatible migrator.

pub mod flyway;
pub mod release_cli;
pub mod schema;
pub mod sqlite;

use sqlx::PgPool;
use sqlx::postgres::{PgConnectOptions, PgPoolOptions};

use crate::config::Datasource;

pub fn connect_options(ds: &Datasource) -> PgConnectOptions {
    PgConnectOptions::new()
        .host(&ds.host)
        .port(ds.port)
        .database(&ds.database)
        .username(&ds.username)
        .password(&ds.password)
        .application_name("picsou")
}

pub async fn connect(ds: &Datasource) -> Result<PgPool, sqlx::Error> {
    PgPoolOptions::new()
        .max_connections(ds.max_connections)
        .acquire_timeout(ds.connect_timeout)
        .connect_with(connect_options(ds))
        .await
}
