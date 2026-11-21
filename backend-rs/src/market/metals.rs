//! PreciousMetal: the gold products a PRECIOUS_METALS holding can carry. Their live prices come
//! from the Loomis FXGS quote page (PreciousMetalPriceProvider), not ported yet: they are valued
//! from the last recorded price.

use serde::Serialize;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum Unit {
    Gram,
    Coin,
}

/// (ticker, display name, unit), in registry order.
pub const CATALOGUE: [(&str, &str, Unit); 16] = [
    ("GOLD:GRAM", "Gold (fine gram)", Unit::Gram),
    ("GOLD:NAPOLEON-20F", "Napoléon 20 F", Unit::Coin),
    ("GOLD:NAPOLEON-10F", "Napoléon 10 F", Unit::Coin),
    ("GOLD:SWISS-20F", "20 Francs Suisse", Unit::Coin),
    ("GOLD:LATIN-UNION-20F", "Union latine 20 F", Unit::Coin),
    ("GOLD:TUNISIA-20F", "20 Francs Tunisie", Unit::Coin),
    ("GOLD:SOVEREIGN", "Souverain", Unit::Coin),
    ("GOLD:SOVEREIGN-EII", "Souverain Elizabeth II", Unit::Coin),
    ("GOLD:HALF-SOVEREIGN", "Demi-souverain", Unit::Coin),
    ("GOLD:KRUGERRAND", "Krugerrand", Unit::Coin),
    ("GOLD:USD-20", "20 Dollars US", Unit::Coin),
    ("GOLD:USD-10", "10 Dollars US", Unit::Coin),
    ("GOLD:USD-5", "5 Dollars US", Unit::Coin),
    ("GOLD:PESO-50", "50 Pesos", Unit::Coin),
    ("GOLD:FLORIN-10", "10 Florins", Unit::Coin),
    ("GOLD:REICHSMARK-20", "20 Reichsmark", Unit::Coin),
];

/// PreciousMetal.fromTicker, case-insensitive.
pub fn supports(ticker: &str) -> bool {
    let upper = ticker.trim().to_uppercase();
    CATALOGUE.iter().any(|(t, _, _)| *t == upper)
}

/// PreciousMetalProductResponse.
#[derive(Debug, Clone, PartialEq, Serialize)]
pub struct ProductResponse {
    pub ticker: &'static str,
    pub name: &'static str,
    pub unit: Unit,
}

pub fn products() -> Vec<ProductResponse> {
    CATALOGUE
        .iter()
        .map(|(ticker, name, unit)| ProductResponse {
            ticker,
            name,
            unit: *unit,
        })
        .collect()
}
