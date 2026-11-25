//! The codes java.util.Currency.getInstance accepts on JDK 21 (Currency.getAvailableCurrencies),
//! historical ISO 4217 codes included.

const CODES: [&str; 231] = [
    "ADP", "AED", "AFA", "AFN", "ALL", "AMD", "ANG", "AOA", "ARS", "ATS", "AUD", "AWG", "AYM",
    "AZM", "AZN", "BAM", "BBD", "BDT", "BEF", "BGL", "BGN", "BHD", "BIF", "BMD", "BND", "BOB",
    "BOV", "BRL", "BSD", "BTN", "BWP", "BYB", "BYN", "BYR", "BZD", "CAD", "CDF", "CHE", "CHF",
    "CHW", "CLF", "CLP", "CNY", "COP", "COU", "CRC", "CSD", "CUC", "CUP", "CVE", "CYP", "CZK",
    "DEM", "DJF", "DKK", "DOP", "DZD", "EEK", "EGP", "ERN", "ESP", "ETB", "EUR", "FIM", "FJD",
    "FKP", "FRF", "GBP", "GEL", "GHC", "GHS", "GIP", "GMD", "GNF", "GRD", "GTQ", "GWP", "GYD",
    "HKD", "HNL", "HRK", "HTG", "HUF", "IDR", "IEP", "ILS", "INR", "IQD", "IRR", "ISK", "ITL",
    "JMD", "JOD", "JPY", "KES", "KGS", "KHR", "KMF", "KPW", "KRW", "KWD", "KYD", "KZT", "LAK",
    "LBP", "LKR", "LRD", "LSL", "LTL", "LUF", "LVL", "LYD", "MAD", "MDL", "MGA", "MGF", "MKD",
    "MMK", "MNT", "MOP", "MRO", "MRU", "MTL", "MUR", "MVR", "MWK", "MXN", "MXV", "MYR", "MZM",
    "MZN", "NAD", "NGN", "NIO", "NLG", "NOK", "NPR", "NZD", "OMR", "PAB", "PEN", "PGK", "PHP",
    "PKR", "PLN", "PTE", "PYG", "QAR", "ROL", "RON", "RSD", "RUB", "RUR", "RWF", "SAR", "SBD",
    "SCR", "SDD", "SDG", "SEK", "SGD", "SHP", "SIT", "SKK", "SLE", "SLL", "SOS", "SRD", "SRG",
    "SSP", "STD", "STN", "SVC", "SYP", "SZL", "THB", "TJS", "TMM", "TMT", "TND", "TOP", "TPE",
    "TRL", "TRY", "TTD", "TWD", "TZS", "UAH", "UGX", "USD", "USN", "USS", "UYI", "UYU", "UZS",
    "VEB", "VED", "VEF", "VES", "VND", "VUV", "WST", "XAF", "XAG", "XAU", "XBA", "XBB", "XBC",
    "XBD", "XCD", "XCG", "XDR", "XFO", "XFU", "XOF", "XPD", "XPF", "XPT", "XSU", "XTS", "XUA",
    "XXX", "YER", "YUM", "ZAR", "ZMK", "ZMW", "ZWD", "ZWL", "ZWN", "ZWR",
];

pub fn is_java_currency(code: &str) -> bool {
    CODES.binary_search(&code).is_ok()
}
