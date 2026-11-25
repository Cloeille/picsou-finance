//! The route registry: every module registers its routes here, so the full (method, path) list
//! is known and the coverage test can compare it with the Java endpoints.

use axum::Router;
use axum::handler::Handler;
use axum::routing::{MethodFilter, on};
use http::Method;

use crate::AppState;
use crate::auth::layer::PermitRule;

#[derive(Default)]
pub struct Routes {
    router: Router<AppState>,
    list: Vec<(Method, String)>,
    permits: Vec<PermitRule>,
}

impl Routes {
    /// Lets anonymous requests through the security chain, as a Spring permitAll matcher:
    /// * matches one path segment, ** the rest. None matches every method.
    pub fn permit_all(&mut self, method: Option<Method>, pattern: &str) -> &mut Self {
        self.permits.push(PermitRule {
            method,
            pattern: pattern.to_owned(),
        });
        self
    }

    /// Registers a handler. Paths use the Spring syntax, which axum 0.8 shares: /api/x/{id}.
    pub fn route<H, T>(&mut self, method: Method, path: &str, handler: H) -> &mut Self
    where
        H: Handler<T, AppState>,
        T: 'static,
    {
        let filter = MethodFilter::try_from(method.clone()).expect("a standard HTTP method");
        let router = std::mem::take(&mut self.router);
        self.router = router.route(path, on(filter, handler));
        self.list.push((method, path.to_owned()));
        self
    }

    pub fn get<H: Handler<T, AppState>, T: 'static>(
        &mut self,
        path: &str,
        handler: H,
    ) -> &mut Self {
        self.route(Method::GET, path, handler)
    }

    pub fn post<H: Handler<T, AppState>, T: 'static>(
        &mut self,
        path: &str,
        handler: H,
    ) -> &mut Self {
        self.route(Method::POST, path, handler)
    }

    pub fn put<H: Handler<T, AppState>, T: 'static>(
        &mut self,
        path: &str,
        handler: H,
    ) -> &mut Self {
        self.route(Method::PUT, path, handler)
    }

    pub fn patch<H: Handler<T, AppState>, T: 'static>(
        &mut self,
        path: &str,
        handler: H,
    ) -> &mut Self {
        self.route(Method::PATCH, path, handler)
    }

    pub fn delete<H: Handler<T, AppState>, T: 'static>(
        &mut self,
        path: &str,
        handler: H,
    ) -> &mut Self {
        self.route(Method::DELETE, path, handler)
    }

    /// Every registered (method, path), in registration order.
    pub fn list(&self) -> &[(Method, String)] {
        &self.list
    }

    pub fn into_parts(self) -> (Router<AppState>, Vec<PermitRule>) {
        (self.router, self.permits)
    }
}

/// Every module's routes. A track adds its routes in its own module's register function.
pub fn all() -> Routes {
    let mut routes = Routes::default();
    crate::actuator::register(&mut routes);
    crate::setup_auth::register(&mut routes);
    crate::identity::register(&mut routes);
    crate::admin::register(&mut routes);
    crate::ledger::register(&mut routes);
    crate::accounts::register(&mut routes);
    crate::family::register(&mut routes);
    crate::budget::register(&mut routes);
    crate::wealth::register(&mut routes);
    crate::market::register(&mut routes);
    crate::sync::register(&mut routes);
    crate::connectors::register(&mut routes);
    crate::transfer::register(&mut routes);
    crate::jobs::register(&mut routes);
    crate::mcp::register(&mut routes);
    crate::ai::register(&mut routes);
    routes
}
