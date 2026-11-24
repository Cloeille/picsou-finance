//! Family members and the family view (track T2).

pub mod members;
pub mod sharing;
pub mod username;
pub mod view;

use crate::routes::Routes;

pub fn register(routes: &mut Routes) {
    members::register(routes);
    sharing::register(routes);
    view::register(routes);
}
