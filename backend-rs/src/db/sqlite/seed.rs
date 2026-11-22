//! Idempotent reference seed data for fresh SQLite databases.
//!
//! Mirrors the exact reference rows inserted by PostgreSQL Flyway migrations `V1..V155`:
//! - `app_setting` defaults (`V25`, `V31`, `V62`, `V95`)
//! - `crypto_asset` curated tickers (`V122`)
//! - `merchant_brand` and `merchant_alias` offline merchant knowledge base (`V39`)
//!
//! Kept intentionally separate from `schema.sql` DDL so `export_pg_to_sqlite` can mirror any live
//! PostgreSQL database 1:1 without seed collisions, while fresh SQLite bootstrap calls
//! [`ensure_seeded`] idempotently after [`SqliteDb::migrate`].

use rusqlite::{Connection, TransactionBehavior, params};

use super::{SqliteDb, SqliteError};

/// Default rows in `app_setting` (excluding `setup.state`, which depends on `app_user`).
const DEFAULT_APP_SETTINGS: &[(&str, &str)] = &[
    // V25__setup_state.sql
    ("integration.enablebanking.enabled", "false"),
    ("integration.boursobank.enabled", "false"),
    ("integration.traderepublic.enabled", "false"),
    ("integration.finary.enabled", "false"),
    ("integration.crypto.enabled", "false"),
    // V31__price_cleanup_gate.sql
    ("price.fx_fix_cleanup_done", "false"),
    // V62__bourse_direct_integration.sql
    ("integration.boursedirect.enabled", "false"),
    // V95__fortuneo_integration.sql
    ("integration.fortuneo.enabled", "false"),
];

/// Curated crypto registry rows from `V122__crypto_asset.sql`.
const CURATED_CRYPTO_ASSETS: &[(&str, &str, &str)] = &[
    ("BTC", "bitcoin", "Bitcoin"),
    ("ETH", "ethereum", "Ethereum"),
    ("SOL", "solana", "Solana"),
    ("BNB", "binancecoin", "BNB"),
    ("ADA", "cardano", "Cardano"),
    ("XRP", "ripple", "XRP"),
    ("DOGE", "dogecoin", "Dogecoin"),
    ("DOT", "polkadot", "Polkadot"),
    ("MATIC", "matic-network", "MATIC (migrated to POL)"),
    ("POL", "polygon-ecosystem-token", "POL (ex-MATIC)"),
    ("AVAX", "avalanche-2", "Avalanche"),
    ("USDT", "tether", "Tether"),
    ("USDC", "usd-coin", "USDC"),
    ("DAI", "dai", "Dai"),
    ("EURC", "euro-coin", "EURC"),
    ("LINK", "chainlink", "Chainlink"),
    ("UNI", "uniswap", "Uniswap"),
    ("ATOM", "cosmos", "Cosmos Hub"),
    ("LTC", "litecoin", "Litecoin"),
    ("NEAR", "near", "NEAR Protocol"),
    ("ARB", "arbitrum", "Arbitrum"),
    ("OP", "optimism", "Optimism"),
    ("SHIB", "shiba-inu", "Shiba Inu"),
    ("PEPE", "pepe", "Pepe"),
    ("SUI", "sui", "Sui"),
    ("EGLD", "elrond-erd-2", "MultiversX"),
    ("XTZ", "tezos", "Tezos"),
    ("ALGO", "algorand", "Algorand"),
    ("KSM", "kusama", "Kusama"),
    ("TIA", "celestia", "Celestia"),
    ("INJ", "injective-protocol", "Injective"),
    ("ROSE", "oasis-network", "Oasis"),
    ("KAVA", "kava", "Kava"),
    ("ZEC", "zcash", "Zcash"),
    ("ETC", "ethereum-classic", "Ethereum Classic"),
    ("XLM", "stellar", "Stellar"),
    ("TRX", "tron", "TRON"),
    ("BCH", "bitcoin-cash", "Bitcoin Cash"),
    ("MINA", "mina-protocol", "Mina Protocol"),
    ("OSMO", "osmosis", "Osmosis"),
    ("AKT", "akash-network", "Akash Network"),
    ("DYDX", "dydx-chain", "dYdX"),
    ("CELO", "celo", "Celo"),
    ("BAND", "band-protocol", "Band"),
    ("XMR", "monero", "Monero"),
    ("VET", "vechain", "VeChain"),
    ("HBAR", "hedera-hashgraph", "Hedera"),
    ("GRT", "the-graph", "The Graph"),
    ("NEXO", "nexo", "NEXO"),
    ("CRO", "crypto-com-chain", "Cronos"),
];

type MerchantBrandSeed = (
    &'static str,
    &'static str,
    &'static str,
    &'static str,
    &'static str,
    Option<&'static str>,
);

/// Offline merchant brand catalog from `V39__merchant_knowledge_base.sql`.
/// `(slug, display_name, default_category_slug, color, monogram, logo_domain)`
const MERCHANT_BRANDS: &[MerchantBrandSeed] = &[
    // Courses (groceries)
    (
        "carrefour",
        "Carrefour",
        "courses",
        "#0066CB",
        "C",
        Some("carrefour.fr"),
    ),
    (
        "leclerc",
        "E.Leclerc",
        "courses",
        "#0066B3",
        "L",
        Some("leclerc.com"),
    ),
    (
        "auchan",
        "Auchan",
        "courses",
        "#E2001A",
        "A",
        Some("auchan.fr"),
    ),
    (
        "intermarche",
        "Intermarché",
        "courses",
        "#E20613",
        "I",
        Some("intermarche.com"),
    ),
    ("lidl", "Lidl", "courses", "#0050AA", "L", Some("lidl.fr")),
    ("aldi", "Aldi", "courses", "#1E3A8A", "A", Some("aldi.fr")),
    (
        "casino",
        "Casino",
        "courses",
        "#E2001A",
        "C",
        Some("casino.fr"),
    ),
    (
        "monoprix",
        "Monoprix",
        "courses",
        "#1D1D1B",
        "M",
        Some("monoprix.fr"),
    ),
    (
        "franprix",
        "Franprix",
        "courses",
        "#E2001A",
        "F",
        Some("franprix.fr"),
    ),
    (
        "super-u",
        "Super U",
        "courses",
        "#E2001A",
        "U",
        Some("magasins-u.com"),
    ),
    ("cora", "Cora", "courses", "#E2001A", "C", Some("cora.fr")),
    (
        "picard",
        "Picard",
        "courses",
        "#003DA5",
        "P",
        Some("picard.fr"),
    ),
    (
        "grand-frais",
        "Grand Frais",
        "courses",
        "#7AB51D",
        "GF",
        Some("grandfrais.com"),
    ),
    (
        "naturalia",
        "Naturalia",
        "courses",
        "#5A8F29",
        "N",
        Some("naturalia.fr"),
    ),
    (
        "biocoop",
        "Biocoop",
        "courses",
        "#6BA539",
        "B",
        Some("biocoop.fr"),
    ),
    // Restaurants & food delivery
    (
        "mcdonalds",
        "McDonald's",
        "restaurants",
        "#FFC72C",
        "M",
        Some("mcdonalds.fr"),
    ),
    (
        "burger-king",
        "Burger King",
        "restaurants",
        "#D62300",
        "BK",
        Some("burgerking.fr"),
    ),
    ("kfc", "KFC", "restaurants", "#A3080C", "K", Some("kfc.fr")),
    (
        "quick",
        "Quick",
        "restaurants",
        "#E2001A",
        "Q",
        Some("quick.fr"),
    ),
    (
        "subway",
        "Subway",
        "restaurants",
        "#008C15",
        "S",
        Some("subway.com"),
    ),
    (
        "dominos",
        "Domino's Pizza",
        "restaurants",
        "#006491",
        "D",
        Some("dominos.fr"),
    ),
    (
        "pizza-hut",
        "Pizza Hut",
        "restaurants",
        "#E3000F",
        "PH",
        Some("pizzahut.fr"),
    ),
    (
        "starbucks",
        "Starbucks",
        "restaurants",
        "#00704A",
        "S",
        Some("starbucks.fr"),
    ),
    (
        "five-guys",
        "Five Guys",
        "restaurants",
        "#ED174F",
        "FG",
        Some("fiveguys.fr"),
    ),
    (
        "otacos",
        "O'Tacos",
        "restaurants",
        "#1D1D1B",
        "OT",
        Some("otacos.fr"),
    ),
    (
        "sushi-shop",
        "Sushi Shop",
        "restaurants",
        "#1D1D1B",
        "SS",
        Some("sushishop.fr"),
    ),
    (
        "brioche-doree",
        "Brioche Dorée",
        "restaurants",
        "#C8102E",
        "BD",
        Some("briochedoree.fr"),
    ),
    (
        "flunch",
        "Flunch",
        "restaurants",
        "#E2001A",
        "F",
        Some("flunch.fr"),
    ),
    (
        "buffalo-grill",
        "Buffalo Grill",
        "restaurants",
        "#C8102E",
        "BG",
        Some("buffalo-grill.fr"),
    ),
    (
        "hippopotamus",
        "Hippopotamus",
        "restaurants",
        "#8B1A1A",
        "H",
        Some("hippopotamus.fr"),
    ),
    (
        "deliveroo",
        "Deliveroo",
        "restaurants",
        "#00CCBC",
        "D",
        Some("deliveroo.fr"),
    ),
    (
        "uber-eats",
        "Uber Eats",
        "restaurants",
        "#06C167",
        "UE",
        Some("ubereats.com"),
    ),
    (
        "just-eat",
        "Just Eat",
        "restaurants",
        "#FF8000",
        "JE",
        Some("just-eat.fr"),
    ),
    // Transport & fuel
    (
        "sncf",
        "SNCF",
        "transport",
        "#2D2A5F",
        "S",
        Some("sncf.com"),
    ),
    ("ratp", "RATP", "transport", "#00643C", "R", Some("ratp.fr")),
    (
        "uber",
        "Uber",
        "transport",
        "#000000",
        "U",
        Some("uber.com"),
    ),
    ("bolt", "Bolt", "transport", "#34D186", "B", Some("bolt.eu")),
    (
        "blablacar",
        "BlaBlaCar",
        "transport",
        "#00AFF5",
        "BB",
        Some("blablacar.fr"),
    ),
    (
        "shell",
        "Shell",
        "transport",
        "#FBCE07",
        "S",
        Some("shell.fr"),
    ),
    ("esso", "Esso", "transport", "#CE1126", "E", Some("esso.fr")),
    ("bp", "BP", "transport", "#009900", "BP", Some("bp.com")),
    ("avia", "Avia", "transport", "#E2001A", "A", Some("avia.fr")),
    (
        "total",
        "TotalEnergies",
        "transport",
        "#ED1C24",
        "T",
        Some("totalenergies.fr"),
    ),
    (
        "vinci-autoroutes",
        "Vinci Autoroutes",
        "transport",
        "#C8102E",
        "VA",
        Some("vinci-autoroutes.com"),
    ),
    (
        "ouigo",
        "OUIGO",
        "transport",
        "#E5007D",
        "O",
        Some("ouigo.com"),
    ),
    (
        "trainline",
        "Trainline",
        "transport",
        "#00A88E",
        "T",
        Some("thetrainline.com"),
    ),
    (
        "flixbus",
        "FlixBus",
        "transport",
        "#73D700",
        "FB",
        Some("flixbus.fr"),
    ),
    (
        "navigo",
        "Navigo",
        "transport",
        "#2D2A5F",
        "N",
        Some("navigo.fr"),
    ),
    ("lime", "Lime", "transport", "#00DD00", "L", Some("li.me")),
    (
        "velib",
        "Vélib'",
        "transport",
        "#00A651",
        "V",
        Some("velib-metropole.fr"),
    ),
    // Factures & énergie
    ("edf", "EDF", "factures", "#001E96", "E", Some("edf.fr")),
    (
        "engie",
        "Engie",
        "factures",
        "#00AAFF",
        "E",
        Some("engie.fr"),
    ),
    (
        "veolia",
        "Veolia",
        "factures",
        "#00B0CA",
        "V",
        Some("veolia.fr"),
    ),
    ("suez", "Suez", "factures", "#00AEEF", "S", Some("suez.com")),
    (
        "orange",
        "Orange",
        "factures",
        "#FF7900",
        "O",
        Some("orange.fr"),
    ),
    ("sfr", "SFR", "factures", "#E2001A", "S", Some("sfr.fr")),
    (
        "bouygues-telecom",
        "Bouygues Telecom",
        "factures",
        "#0099D8",
        "BT",
        Some("bouyguestelecom.fr"),
    ),
    ("free", "Free", "factures", "#CD1E25", "F", Some("free.fr")),
    ("sosh", "Sosh", "factures", "#E5007D", "S", Some("sosh.fr")),
    (
        "red-sfr",
        "RED by SFR",
        "factures",
        "#E2001A",
        "R",
        Some("red-by-sfr.fr"),
    ),
    (
        "la-poste-mobile",
        "La Poste Mobile",
        "factures",
        "#FFCC00",
        "LP",
        Some("lapostemobile.fr"),
    ),
    // Santé
    (
        "doctolib",
        "Doctolib",
        "sante",
        "#107ACA",
        "D",
        Some("doctolib.fr"),
    ),
    ("pharmacie", "Pharmacie", "sante", "#2E9B57", "+", None),
    ("krys", "Krys", "sante", "#E2001A", "K", Some("krys.com")),
    (
        "afflelou",
        "Alain Afflelou",
        "sante",
        "#1D1D1B",
        "A",
        Some("afflelou.com"),
    ),
    (
        "optic-2000",
        "Optic 2000",
        "sante",
        "#003DA5",
        "O2",
        Some("optic2000.com"),
    ),
    // Loisirs
    ("fnac", "Fnac", "loisirs", "#E2A700", "F", Some("fnac.com")),
    (
        "decathlon",
        "Decathlon",
        "loisirs",
        "#0082C3",
        "D",
        Some("decathlon.fr"),
    ),
    ("ugc", "UGC", "loisirs", "#E2001A", "U", Some("ugc.fr")),
    (
        "pathe",
        "Pathé",
        "loisirs",
        "#E2001A",
        "P",
        Some("pathe.fr"),
    ),
    (
        "cultura",
        "Cultura",
        "loisirs",
        "#E94E1B",
        "C",
        Some("cultura.com"),
    ),
    (
        "micromania",
        "Micromania",
        "loisirs",
        "#E2001A",
        "M",
        Some("micromania.fr"),
    ),
    (
        "steam",
        "Steam",
        "loisirs",
        "#1B2838",
        "S",
        Some("steampowered.com"),
    ),
    (
        "playstation",
        "PlayStation",
        "loisirs",
        "#003791",
        "PS",
        Some("playstation.com"),
    ),
    (
        "nintendo",
        "Nintendo",
        "loisirs",
        "#E60012",
        "N",
        Some("nintendo.fr"),
    ),
    (
        "basic-fit",
        "Basic-Fit",
        "loisirs",
        "#FF6600",
        "BF",
        Some("basic-fit.com"),
    ),
    (
        "fitness-park",
        "Fitness Park",
        "loisirs",
        "#1D1D1B",
        "FP",
        Some("fitnesspark.fr"),
    ),
    // Shopping
    (
        "amazon",
        "Amazon",
        "shopping",
        "#FF9900",
        "A",
        Some("amazon.fr"),
    ),
    (
        "zalando",
        "Zalando",
        "shopping",
        "#FF6900",
        "Z",
        Some("zalando.fr"),
    ),
    ("zara", "Zara", "shopping", "#1D1D1B", "Z", Some("zara.com")),
    ("hm", "H&M", "shopping", "#E50010", "H", Some("hm.com")),
    (
        "uniqlo",
        "Uniqlo",
        "shopping",
        "#FF0000",
        "U",
        Some("uniqlo.com"),
    ),
    (
        "primark",
        "Primark",
        "shopping",
        "#0066B3",
        "P",
        Some("primark.com"),
    ),
    (
        "kiabi",
        "Kiabi",
        "shopping",
        "#E5007D",
        "K",
        Some("kiabi.com"),
    ),
    (
        "sephora",
        "Sephora",
        "shopping",
        "#1D1D1B",
        "S",
        Some("sephora.fr"),
    ),
    (
        "nocibe",
        "Nocibé",
        "shopping",
        "#E5007D",
        "N",
        Some("nocibe.fr"),
    ),
    (
        "marionnaud",
        "Marionnaud",
        "shopping",
        "#1D1D1B",
        "M",
        Some("marionnaud.fr"),
    ),
    (
        "darty",
        "Darty",
        "shopping",
        "#E2001A",
        "D",
        Some("darty.com"),
    ),
    (
        "boulanger",
        "Boulanger",
        "shopping",
        "#E2001A",
        "B",
        Some("boulanger.com"),
    ),
    (
        "cdiscount",
        "Cdiscount",
        "shopping",
        "#ED7203",
        "C",
        Some("cdiscount.com"),
    ),
    (
        "vinted",
        "Vinted",
        "shopping",
        "#09B1BA",
        "V",
        Some("vinted.fr"),
    ),
    (
        "shein",
        "Shein",
        "shopping",
        "#1D1D1B",
        "S",
        Some("shein.com"),
    ),
    (
        "aliexpress",
        "AliExpress",
        "shopping",
        "#E62E04",
        "AE",
        Some("aliexpress.com"),
    ),
    ("nike", "Nike", "shopping", "#1D1D1B", "N", Some("nike.com")),
    (
        "adidas",
        "Adidas",
        "shopping",
        "#1D1D1B",
        "A",
        Some("adidas.fr"),
    ),
    (
        "action",
        "Action",
        "shopping",
        "#E30613",
        "A",
        Some("action.com"),
    ),
    ("gifi", "GiFi", "shopping", "#E2001A", "G", Some("gifi.fr")),
    (
        "la-redoute",
        "La Redoute",
        "shopping",
        "#1D1D1B",
        "LR",
        Some("laredoute.fr"),
    ),
    // Logement
    ("ikea", "IKEA", "logement", "#0058A3", "I", Some("ikea.com")),
    (
        "leroy-merlin",
        "Leroy Merlin",
        "logement",
        "#7AB51D",
        "LM",
        Some("leroymerlin.fr"),
    ),
    (
        "castorama",
        "Castorama",
        "logement",
        "#0078C1",
        "C",
        Some("castorama.fr"),
    ),
    (
        "brico-depot",
        "Brico Dépôt",
        "logement",
        "#E2001A",
        "BD",
        Some("bricodepot.fr"),
    ),
    (
        "maisons-du-monde",
        "Maisons du Monde",
        "logement",
        "#1D1D1B",
        "MM",
        Some("maisonsdumonde.com"),
    ),
    (
        "conforama",
        "Conforama",
        "logement",
        "#E2001A",
        "C",
        Some("conforama.fr"),
    ),
    // Abonnements
    (
        "netflix",
        "Netflix",
        "abonnements",
        "#E50914",
        "N",
        Some("netflix.com"),
    ),
    (
        "spotify",
        "Spotify",
        "abonnements",
        "#1DB954",
        "S",
        Some("spotify.com"),
    ),
    (
        "disney-plus",
        "Disney+",
        "abonnements",
        "#113CCF",
        "D+",
        Some("disneyplus.com"),
    ),
    (
        "amazon-prime",
        "Amazon Prime",
        "abonnements",
        "#00A8E1",
        "P",
        Some("primevideo.com"),
    ),
    (
        "canal-plus",
        "Canal+",
        "abonnements",
        "#1D1D1B",
        "C+",
        Some("canalplus.com"),
    ),
    (
        "deezer",
        "Deezer",
        "abonnements",
        "#FEAA2D",
        "D",
        Some("deezer.com"),
    ),
    (
        "youtube-premium",
        "YouTube Premium",
        "abonnements",
        "#FF0000",
        "YT",
        Some("youtube.com"),
    ),
    (
        "audible",
        "Audible",
        "abonnements",
        "#FF9900",
        "A",
        Some("audible.fr"),
    ),
    (
        "apple",
        "Apple",
        "abonnements",
        "#1D1D1B",
        "A",
        Some("apple.com"),
    ),
    (
        "microsoft",
        "Microsoft",
        "abonnements",
        "#0078D4",
        "M",
        Some("microsoft.com"),
    ),
    (
        "google",
        "Google",
        "abonnements",
        "#4285F4",
        "G",
        Some("google.com"),
    ),
    (
        "adobe",
        "Adobe",
        "abonnements",
        "#FA0F00",
        "A",
        Some("adobe.com"),
    ),
    (
        "openai",
        "OpenAI",
        "abonnements",
        "#10A37F",
        "AI",
        Some("openai.com"),
    ),
    (
        "dropbox",
        "Dropbox",
        "abonnements",
        "#0061FF",
        "D",
        Some("dropbox.com"),
    ),
    (
        "linkedin",
        "LinkedIn",
        "abonnements",
        "#0A66C2",
        "in",
        Some("linkedin.com"),
    ),
    (
        "paramount-plus",
        "Paramount+",
        "abonnements",
        "#0064FF",
        "P+",
        Some("paramountplus.com"),
    ),
    (
        "crunchyroll",
        "Crunchyroll",
        "abonnements",
        "#F47521",
        "C",
        Some("crunchyroll.com"),
    ),
    (
        "twitch",
        "Twitch",
        "abonnements",
        "#9146FF",
        "T",
        Some("twitch.tv"),
    ),
    (
        "patreon",
        "Patreon",
        "abonnements",
        "#FF424D",
        "P",
        Some("patreon.com"),
    ),
    // Voyages
    (
        "airbnb",
        "Airbnb",
        "voyages",
        "#FF5A5F",
        "A",
        Some("airbnb.fr"),
    ),
    (
        "booking",
        "Booking.com",
        "voyages",
        "#003580",
        "B",
        Some("booking.com"),
    ),
    (
        "air-france",
        "Air France",
        "voyages",
        "#002157",
        "AF",
        Some("airfrance.fr"),
    ),
    (
        "ryanair",
        "Ryanair",
        "voyages",
        "#073590",
        "R",
        Some("ryanair.com"),
    ),
    (
        "easyjet",
        "easyJet",
        "voyages",
        "#FF6600",
        "eJ",
        Some("easyjet.com"),
    ),
    (
        "transavia",
        "Transavia",
        "voyages",
        "#00D1B0",
        "T",
        Some("transavia.com"),
    ),
    (
        "expedia",
        "Expedia",
        "voyages",
        "#FFC72C",
        "E",
        Some("expedia.fr"),
    ),
    (
        "accor",
        "Accor",
        "voyages",
        "#1D1D1B",
        "A",
        Some("accor.com"),
    ),
    ("ibis", "Ibis", "voyages", "#E2001A", "I", Some("ibis.com")),
    (
        "eurostar",
        "Eurostar",
        "voyages",
        "#FFD800",
        "E",
        Some("eurostar.com"),
    ),
    (
        "vueling",
        "Vueling",
        "voyages",
        "#FFCC00",
        "V",
        Some("vueling.com"),
    ),
    (
        "bb-hotels",
        "B&B Hotels",
        "voyages",
        "#E2001A",
        "BB",
        Some("hotel-bb.com"),
    ),
    // Income
    (
        "ameli",
        "Ameli",
        "remboursements",
        "#005BA4",
        "A",
        Some("ameli.fr"),
    ),
    (
        "caf",
        "CAF",
        "autres-revenus",
        "#0096D6",
        "C",
        Some("caf.fr"),
    ),
];

/// Offline merchant aliases from `V39__merchant_knowledge_base.sql`.
/// `(brand_slug, pattern, match_type)`
const MERCHANT_ALIASES: &[(&str, &str, &str)] = &[
    // Courses
    ("carrefour", "carrefour", "WORD"),
    ("leclerc", "leclerc", "WORD"),
    ("auchan", "auchan", "WORD"),
    ("intermarche", "intermarche", "WORD"),
    ("intermarche", "itm", "WORD"),
    ("lidl", "lidl", "WORD"),
    ("aldi", "aldi", "WORD"),
    ("casino", "casino", "WORD"),
    ("monoprix", "monoprix", "WORD"),
    ("monoprix", "monop", "WORD"),
    ("franprix", "franprix", "WORD"),
    ("super-u", "superu", "WORD"),
    ("super-u", "super u", "PHRASE"),
    ("super-u", "hyper u", "PHRASE"),
    ("super-u", "systeme u", "PHRASE"),
    ("cora", "cora", "WORD"),
    ("picard", "picard", "WORD"),
    ("grand-frais", "grandfrais", "WORD"),
    ("grand-frais", "grand frais", "PHRASE"),
    ("naturalia", "naturalia", "WORD"),
    ("biocoop", "biocoop", "WORD"),
    // Restaurants
    ("mcdonalds", "mcdonalds", "WORD"),
    ("mcdonalds", "mcdo", "WORD"),
    ("mcdonalds", "mc donalds", "PHRASE"),
    ("burger-king", "burgerking", "WORD"),
    ("burger-king", "burger king", "PHRASE"),
    ("kfc", "kfc", "WORD"),
    ("quick", "quick", "WORD"),
    ("subway", "subway", "WORD"),
    ("dominos", "dominos", "WORD"),
    ("dominos", "dominos pizza", "PHRASE"),
    ("pizza-hut", "pizzahut", "WORD"),
    ("pizza-hut", "pizza hut", "PHRASE"),
    ("starbucks", "starbucks", "WORD"),
    ("five-guys", "fiveguys", "WORD"),
    ("five-guys", "five guys", "PHRASE"),
    ("otacos", "otacos", "WORD"),
    ("sushi-shop", "sushishop", "WORD"),
    ("sushi-shop", "sushi shop", "PHRASE"),
    ("brioche-doree", "briochedoree", "WORD"),
    ("brioche-doree", "brioche doree", "PHRASE"),
    ("flunch", "flunch", "WORD"),
    ("buffalo-grill", "buffalogrill", "WORD"),
    ("buffalo-grill", "buffalo grill", "PHRASE"),
    ("hippopotamus", "hippopotamus", "WORD"),
    ("hippopotamus", "hippo", "WORD"),
    ("deliveroo", "deliveroo", "WORD"),
    ("uber-eats", "ubereats", "WORD"),
    ("uber-eats", "uber eats", "PHRASE"),
    ("just-eat", "justeat", "WORD"),
    ("just-eat", "just eat", "PHRASE"),
    // Transport
    ("sncf", "sncf", "WORD"),
    ("ratp", "ratp", "WORD"),
    ("uber", "uber", "WORD"),
    ("bolt", "bolt", "WORD"),
    ("blablacar", "blablacar", "WORD"),
    ("shell", "shell", "WORD"),
    ("esso", "esso", "WORD"),
    ("bp", "bp", "WORD"),
    ("avia", "avia", "WORD"),
    ("total", "total", "WORD"),
    ("total", "totalenergies", "WORD"),
    ("total", "total access", "PHRASE"),
    ("total", "total energies", "PHRASE"),
    ("vinci-autoroutes", "vinci autoroutes", "PHRASE"),
    ("vinci-autoroutes", "autoroutes", "WORD"),
    ("vinci-autoroutes", "aprr", "WORD"),
    ("vinci-autoroutes", "sanef", "WORD"),
    ("ouigo", "ouigo", "WORD"),
    ("trainline", "trainline", "WORD"),
    ("flixbus", "flixbus", "WORD"),
    ("navigo", "navigo", "WORD"),
    ("lime", "lime", "WORD"),
    ("velib", "velib", "WORD"),
    // Factures
    ("edf", "edf", "WORD"),
    ("engie", "engie", "WORD"),
    ("veolia", "veolia", "WORD"),
    ("suez", "suez", "WORD"),
    ("orange", "orange", "WORD"),
    ("sfr", "sfr", "WORD"),
    ("bouygues-telecom", "bouygues", "WORD"),
    ("bouygues-telecom", "bouygues telecom", "PHRASE"),
    ("free", "freemobile", "WORD"),
    ("free", "iliad", "WORD"),
    ("free", "free mobile", "PHRASE"),
    ("free", "free telecom", "PHRASE"),
    ("free", "free haut debit", "PHRASE"),
    ("sosh", "sosh", "WORD"),
    ("red-sfr", "redbysfr", "WORD"),
    ("red-sfr", "red by sfr", "PHRASE"),
    ("la-poste-mobile", "lapostemobile", "WORD"),
    ("la-poste-mobile", "la poste mobile", "PHRASE"),
    // Santé
    ("doctolib", "doctolib", "WORD"),
    ("pharmacie", "pharmacie", "WORD"),
    ("pharmacie", "pharma", "WORD"),
    ("krys", "krys", "WORD"),
    ("afflelou", "afflelou", "WORD"),
    ("optic-2000", "optic2000", "WORD"),
    ("optic-2000", "optic 2000", "PHRASE"),
    // Loisirs
    ("fnac", "fnac", "WORD"),
    ("decathlon", "decathlon", "WORD"),
    ("ugc", "ugc", "WORD"),
    ("pathe", "pathe", "WORD"),
    ("pathe", "pathe gaumont", "PHRASE"),
    ("cultura", "cultura", "WORD"),
    ("micromania", "micromania", "WORD"),
    ("steam", "steam", "WORD"),
    ("playstation", "playstation", "WORD"),
    ("playstation", "playstation network", "PHRASE"),
    ("nintendo", "nintendo", "WORD"),
    ("basic-fit", "basicfit", "WORD"),
    ("basic-fit", "basic fit", "PHRASE"),
    ("fitness-park", "fitnesspark", "WORD"),
    ("fitness-park", "fitness park", "PHRASE"),
    // Shopping
    ("amazon", "amazon", "WORD"),
    ("amazon", "amzn", "WORD"),
    ("amazon", "amazon mktp", "PHRASE"),
    ("amazon", "amzn mktp", "PHRASE"),
    ("zalando", "zalando", "WORD"),
    ("zara", "zara", "WORD"),
    ("hm", "hm", "WORD"),
    ("hm", "h&m", "PHRASE"),
    ("uniqlo", "uniqlo", "WORD"),
    ("primark", "primark", "WORD"),
    ("kiabi", "kiabi", "WORD"),
    ("sephora", "sephora", "WORD"),
    ("nocibe", "nocibe", "WORD"),
    ("marionnaud", "marionnaud", "WORD"),
    ("darty", "darty", "WORD"),
    ("boulanger", "boulanger", "WORD"),
    ("cdiscount", "cdiscount", "WORD"),
    ("vinted", "vinted", "WORD"),
    ("shein", "shein", "WORD"),
    ("aliexpress", "aliexpress", "WORD"),
    ("aliexpress", "ali express", "PHRASE"),
    ("nike", "nike", "WORD"),
    ("adidas", "adidas", "WORD"),
    ("action", "action", "WORD"),
    ("gifi", "gifi", "WORD"),
    ("la-redoute", "laredoute", "WORD"),
    ("la-redoute", "redoute", "WORD"),
    ("la-redoute", "la redoute", "PHRASE"),
    // Logement
    ("ikea", "ikea", "WORD"),
    ("leroy-merlin", "leroymerlin", "WORD"),
    ("leroy-merlin", "leroy merlin", "PHRASE"),
    ("castorama", "castorama", "WORD"),
    ("castorama", "casto", "WORD"),
    ("brico-depot", "bricodepot", "WORD"),
    ("brico-depot", "brico depot", "PHRASE"),
    ("maisons-du-monde", "maisonsdumonde", "WORD"),
    ("maisons-du-monde", "maisons du monde", "PHRASE"),
    ("conforama", "conforama", "WORD"),
    // Abonnements
    ("netflix", "netflix", "WORD"),
    ("spotify", "spotify", "WORD"),
    ("disney-plus", "disneyplus", "WORD"),
    ("disney-plus", "disney", "WORD"),
    ("disney-plus", "disney plus", "PHRASE"),
    ("amazon-prime", "amazon prime", "PHRASE"),
    ("amazon-prime", "prime video", "PHRASE"),
    ("canal-plus", "canalplus", "WORD"),
    ("canal-plus", "canal", "WORD"),
    ("canal-plus", "canal plus", "PHRASE"),
    ("deezer", "deezer", "WORD"),
    ("youtube-premium", "youtube", "WORD"),
    ("youtube-premium", "youtube premium", "PHRASE"),
    ("audible", "audible", "WORD"),
    ("apple", "apple", "WORD"),
    ("apple", "itunes", "WORD"),
    ("apple", "apple.com", "PHRASE"),
    ("microsoft", "microsoft", "WORD"),
    ("microsoft", "msft", "WORD"),
    ("microsoft", "microsoft 365", "PHRASE"),
    ("google", "google", "WORD"),
    ("google", "google storage", "PHRASE"),
    ("google", "google one", "PHRASE"),
    ("adobe", "adobe", "WORD"),
    ("openai", "openai", "WORD"),
    ("openai", "chatgpt", "WORD"),
    ("openai", "open ai", "PHRASE"),
    ("dropbox", "dropbox", "WORD"),
    ("linkedin", "linkedin", "WORD"),
    ("paramount-plus", "paramountplus", "WORD"),
    ("paramount-plus", "paramount plus", "PHRASE"),
    ("crunchyroll", "crunchyroll", "WORD"),
    ("twitch", "twitch", "WORD"),
    ("patreon", "patreon", "WORD"),
    // Voyages
    ("airbnb", "airbnb", "WORD"),
    ("booking", "booking", "WORD"),
    ("booking", "booking.com", "PHRASE"),
    ("air-france", "airfrance", "WORD"),
    ("air-france", "air france", "PHRASE"),
    ("ryanair", "ryanair", "WORD"),
    ("easyjet", "easyjet", "WORD"),
    ("easyjet", "easy jet", "PHRASE"),
    ("transavia", "transavia", "WORD"),
    ("expedia", "expedia", "WORD"),
    ("accor", "accor", "WORD"),
    ("ibis", "ibis", "WORD"),
    ("eurostar", "eurostar", "WORD"),
    ("vueling", "vueling", "WORD"),
    ("bb-hotels", "bbhotels", "WORD"),
    ("bb-hotels", "b&b hotels", "PHRASE"),
    // Income
    ("ameli", "ameli", "WORD"),
    ("ameli", "cpam", "WORD"),
    ("ameli", "assurance maladie", "PHRASE"),
    ("caf", "caf", "WORD"),
    ("caf", "caisse allocations", "PHRASE"),
];

/// Ensures all reference seed data (`app_setting`, `crypto_asset`, `merchant_brand`,
/// `merchant_alias`) is present on `db` idempotently.
///
/// Safe to invoke on every startup of a SQLite database: existing settings and user customizations
/// are never overwritten, and re-running inserts zero duplicate rows.
pub fn ensure_seeded(db: &SqliteDb) -> Result<(), SqliteError> {
    db.with_conn(ensure_seeded_conn)
}

/// Async variant of [`ensure_seeded`] running on Tokio's blocking pool under the writer gate.
pub async fn ensure_seeded_async(db: &SqliteDb) -> Result<(), SqliteError> {
    db.interact(ensure_seeded_conn).await
}

pub(crate) fn ensure_seeded_conn(conn: &mut Connection) -> Result<(), SqliteError> {
    let tx = conn.transaction_with_behavior(TransactionBehavior::Immediate)?;

    // 1. app_setting: setup.state + default integration/gate flags (never overwrite existing keys).
    tx.execute(
        "INSERT OR IGNORE INTO app_setting (setting_key, value) \
         SELECT 'setup.state', \
                CASE WHEN EXISTS (SELECT 1 FROM app_user) THEN 'COMPLETE' ELSE 'PENDING_ADMIN' END",
        [],
    )?;
    {
        let mut stmt =
            tx.prepare("INSERT OR IGNORE INTO app_setting (setting_key, value) VALUES (?1, ?2)")?;
        for &(key, value) in DEFAULT_APP_SETTINGS {
            stmt.execute(params![key, value])?;
        }
    }

    // 2. crypto_asset: curated CoinGecko mappings.
    {
        let mut stmt = tx.prepare(
            "INSERT OR IGNORE INTO crypto_asset (ticker, coingecko_id, name, source) \
             VALUES (?1, ?2, ?3, 'CURATED')",
        )?;
        for &(ticker, coingecko_id, name) in CURATED_CRYPTO_ASSETS {
            stmt.execute(params![ticker, coingecko_id, name])?;
        }
    }

    // 3. merchant_brand: offline merchant knowledge base.
    {
        let mut stmt = tx.prepare(
            "INSERT OR IGNORE INTO merchant_brand \
             (slug, display_name, default_category_slug, color, monogram, logo_domain) \
             VALUES (?1, ?2, ?3, ?4, ?5, ?6)",
        )?;
        for &(slug, display_name, category_slug, color, monogram, logo_domain) in MERCHANT_BRANDS {
            stmt.execute(params![
                slug,
                display_name,
                category_slug,
                color,
                monogram,
                logo_domain
            ])?;
        }
    }

    // 4. merchant_alias: idempotent lookup by brand slug + (pattern, match_type).
    {
        let mut stmt = tx.prepare(
            "INSERT INTO merchant_alias (brand_id, pattern, match_type) \
             SELECT b.id, ?2, ?3 \
             FROM merchant_brand b \
             WHERE b.slug = ?1 \
               AND NOT EXISTS ( \
                   SELECT 1 FROM merchant_alias a \
                   WHERE a.brand_id = b.id AND a.pattern = ?2 AND a.match_type = ?3 \
               )",
        )?;
        for &(slug, pattern, match_type) in MERCHANT_ALIASES {
            stmt.execute(params![slug, pattern, match_type])?;
        }
    }

    tx.commit()?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn seed_is_idempotent_and_preserves_custom_settings() {
        let db = SqliteDb::open_in_memory().unwrap();
        db.migrate().unwrap();

        ensure_seeded(&db).unwrap();
        let counts_first: (i64, i64, i64, i64) = db
            .with_conn(|conn| {
                Ok((
                    conn.query_row("SELECT COUNT(*) FROM app_setting", [], |r| r.get(0))?,
                    conn.query_row("SELECT COUNT(*) FROM crypto_asset", [], |r| r.get(0))?,
                    conn.query_row("SELECT COUNT(*) FROM merchant_brand", [], |r| r.get(0))?,
                    conn.query_row("SELECT COUNT(*) FROM merchant_alias", [], |r| r.get(0))?,
                ))
            })
            .unwrap();
        assert_eq!(counts_first.0, 9);
        assert_eq!(counts_first.1, 50);
        assert_eq!(counts_first.2, 137);
        assert!(counts_first.3 > 137);

        // Mutate a setting; re-seeding must preserve it and add zero duplicate aliases.
        db.with_conn(|conn| {
            conn.execute(
                "UPDATE app_setting SET value = 'COMPLETE' WHERE setting_key = 'setup.state'",
                [],
            )?;
            Ok(())
        })
        .unwrap();

        ensure_seeded(&db).unwrap();

        let (counts_second, setup_state): ((i64, i64, i64, i64), String) = db
            .with_conn(|conn| {
                Ok((
                    (
                        conn.query_row("SELECT COUNT(*) FROM app_setting", [], |r| r.get(0))?,
                        conn.query_row("SELECT COUNT(*) FROM crypto_asset", [], |r| r.get(0))?,
                        conn.query_row("SELECT COUNT(*) FROM merchant_brand", [], |r| r.get(0))?,
                        conn.query_row("SELECT COUNT(*) FROM merchant_alias", [], |r| r.get(0))?,
                    ),
                    conn.query_row(
                        "SELECT value FROM app_setting WHERE setting_key = 'setup.state'",
                        [],
                        |r| r.get(0),
                    )?,
                ))
            })
            .unwrap();
        assert_eq!(counts_second, counts_first);
        assert_eq!(setup_state, "COMPLETE");
    }
}
