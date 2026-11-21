//! HomeBank import (HomeBankImportController).

use axum::extract::DefaultBodyLimit;
use axum::handler::Handler;

use crate::clock::AppClock;
use crate::error::{AppError, AppResult};
use crate::routes::Routes;
use crate::transfer::upload::MAX_UPLOAD_BYTES;

pub fn register(routes: &mut Routes) {
    routes.post(
        "/api/homebank/import/preview",
        preview.layer(DefaultBodyLimit::max(MAX_UPLOAD_BYTES)),
    );
    routes.post("/api/homebank/import", execute);
}

pub struct Stores;

impl Stores {
    pub fn new(_clock: &AppClock) -> Self {
        Self
    }
}

async fn preview() -> AppResult<()> {
    Err(AppError::NotImplemented)
}

async fn execute() -> AppResult<()> {
    Err(AppError::NotImplemented)
}
