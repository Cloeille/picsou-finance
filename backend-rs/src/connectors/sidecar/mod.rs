//! The generic sidecar layer every bank connector is built on: the HTTP hop and its error
//! contract, the encrypted session row, the queued sync template and the controller helpers.

pub mod base_url;
pub mod client;
pub mod error;
pub mod flow;
pub mod jobs;
pub mod pending;
pub mod plans;
pub mod portfolio;
pub mod session;
pub mod template;
pub mod web;

pub use client::{CallError, RawResponse, SidecarClient, SidecarHttp};
pub use error::{JobError, JobResult, SyncError};
pub use session::{NewSession, SidecarSession, SidecarSyncStatus};
pub use template::{SessionView, SyncJob, SyncStrategy, SyncTemplate};
pub use web::{AuthInitResponse, SessionStatusResponse, SidecarInitiate, SidecarSessionState};
