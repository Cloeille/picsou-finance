//! Full member export as a ZIP (MeExportController).

use crate::error::{AppError, AppResult};
use crate::routes::Routes;

pub fn register(routes: &mut Routes) {
    routes.post("/api/me/export", export);
}

async fn export() -> AppResult<()> {
    Err(AppError::NotImplemented)
}
