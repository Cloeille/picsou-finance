//! Real estate summary and valuations, geocoding (RealEstateController, GeocodingController).

use crate::clock::AppClock;
use crate::config::Config;
use crate::routes::Routes;

pub fn register(_routes: &mut Routes) {}

pub struct State {}

impl State {
    pub fn new(_config: &Config, _http: &reqwest::Client, _clock: &AppClock) -> Self {
        Self {}
    }
}
