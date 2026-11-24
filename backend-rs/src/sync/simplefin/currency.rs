//! java.util.Currency as JDK 21 knows it: which codes getInstance accepts, and which of them
//! have a minor unit (getDefaultFractionDigits() >= 0).

/// Codes with a minor unit, sorted.
const WITH_MINOR_UNIT: &[&str] = &[
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
    "VEB", "VED", "VEF", "VES", "VND", "VUV", "WST", "XAF", "XCD", "XCG", "XOF", "XPF", "YER",
    "YUM", "ZAR", "ZMK", "ZMW", "ZWD", "ZWL", "ZWN", "ZWR",
];

/// Codes Currency.getInstance accepts that have no minor unit (metals, funds, testing), sorted.
const WITHOUT_MINOR_UNIT: &[&str] = &[
    "XAG", "XAU", "XBA", "XBB", "XBC", "XBD", "XDR", "XFO", "XFU", "XPD", "XPT", "XSU", "XTS",
    "XUA", "XXX",
];

/// Currency.getInstance(code) succeeds.
pub fn is_known(code: &str) -> bool {
    has_minor_unit(code) || WITHOUT_MINOR_UNIT.binary_search(&code).is_ok()
}

/// A real currency: a known code with a minor unit.
pub fn has_minor_unit(code: &str) -> bool {
    WITH_MINOR_UNIT.binary_search(&code).is_ok()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_tables_are_sorted_for_binary_search() {
        assert!(WITH_MINOR_UNIT.is_sorted());
        assert!(WITHOUT_MINOR_UNIT.is_sorted());
    }

    #[test]
    fn metals_are_known_but_have_no_minor_unit() {
        assert!(has_minor_unit("USD") && has_minor_unit("JPY"));
        assert!(is_known("XAU") && !has_minor_unit("XAU"));
        assert!(!is_known("ABC") && !is_known("usd"));
    }
}
