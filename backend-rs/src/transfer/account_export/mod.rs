//! Per-account XLSX export (AccountExportController).

use crate::error::{AppError, AppResult};
use crate::routes::Routes;

pub fn register(routes: &mut Routes) {
    routes.post("/api/accounts/export", export);
}

async fn export() -> AppResult<()> {
    Err(AppError::NotImplemented)
}
