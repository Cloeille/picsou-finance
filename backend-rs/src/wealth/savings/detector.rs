//! NameBasedSavingsBookDetector: suggests a savings product from an account name.

use rust_decimal::Decimal;

use super::model::{RegulatedRates, SavingsProduct};

/// SavingsBookSuggestion.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Suggestion {
    pub product: SavingsProduct,
    /// None for COMMERCIAL, whose rate the user supplies.
    pub default_annual_rate: Option<Decimal>,
    pub uncertain: bool,
}

/// Upper case without diacritics, as Normalizer NFD then stripping combining marks does for
/// the Latin letters account names are written in.
pub fn normalise(name: &str) -> String {
    name.chars()
        .filter(|c| !('\u{0300}'..='\u{036F}').contains(c))
        .map(fold)
        .flat_map(char::to_uppercase)
        .collect()
}

fn fold(c: char) -> char {
    match c {
        'à' | 'á' | 'â' | 'ã' | 'ä' | 'å' | 'ā' | 'ă' | 'ą' => 'a',
        'À' | 'Á' | 'Â' | 'Ã' | 'Ä' | 'Å' | 'Ā' | 'Ă' | 'Ą' => 'A',
        'ç' | 'ć' | 'ĉ' | 'ċ' | 'č' => 'c',
        'Ç' | 'Ć' | 'Ĉ' | 'Ċ' | 'Č' => 'C',
        'ď' => 'd',
        'Ď' => 'D',
        'è' | 'é' | 'ê' | 'ë' | 'ē' | 'ĕ' | 'ė' | 'ę' | 'ě' => 'e',
        'È' | 'É' | 'Ê' | 'Ë' | 'Ē' | 'Ĕ' | 'Ė' | 'Ę' | 'Ě' => 'E',
        'ĝ' | 'ğ' | 'ġ' | 'ģ' => 'g',
        'Ĝ' | 'Ğ' | 'Ġ' | 'Ģ' => 'G',
        'ĥ' => 'h',
        'Ĥ' => 'H',
        'ì' | 'í' | 'î' | 'ï' | 'ĩ' | 'ī' | 'ĭ' | 'į' => 'i',
        'Ì' | 'Í' | 'Î' | 'Ï' | 'Ĩ' | 'Ī' | 'Ĭ' | 'Į' | 'İ' => 'I',
        'ĵ' => 'j',
        'Ĵ' => 'J',
        'ķ' => 'k',
        'Ķ' => 'K',
        'ĺ' | 'ļ' | 'ľ' => 'l',
        'Ĺ' | 'Ļ' | 'Ľ' => 'L',
        'ñ' | 'ń' | 'ņ' | 'ň' => 'n',
        'Ñ' | 'Ń' | 'Ņ' | 'Ň' => 'N',
        'ò' | 'ó' | 'ô' | 'õ' | 'ö' | 'ō' | 'ŏ' | 'ő' => 'o',
        'Ò' | 'Ó' | 'Ô' | 'Õ' | 'Ö' | 'Ō' | 'Ŏ' | 'Ő' => 'O',
        'ŕ' | 'ŗ' | 'ř' => 'r',
        'Ŕ' | 'Ŗ' | 'Ř' => 'R',
        'ś' | 'ŝ' | 'ş' | 'š' => 's',
        'Ś' | 'Ŝ' | 'Ş' | 'Š' => 'S',
        'ţ' | 'ť' => 't',
        'Ţ' | 'Ť' => 'T',
        'ù' | 'ú' | 'û' | 'ü' | 'ũ' | 'ū' | 'ŭ' | 'ů' | 'ű' | 'ų' => 'u',
        'Ù' | 'Ú' | 'Û' | 'Ü' | 'Ũ' | 'Ū' | 'Ŭ' | 'Ů' | 'Ű' | 'Ų' => 'U',
        'ŵ' => 'w',
        'Ŵ' => 'W',
        'ý' | 'ÿ' | 'ŷ' => 'y',
        'Ý' | 'Ÿ' | 'Ŷ' => 'Y',
        'ź' | 'ż' | 'ž' => 'z',
        'Ź' | 'Ż' | 'Ž' => 'Z',
        other => other,
    }
}

/// A multi-word token by plain containment; a single word only where it stands alone, so
/// "LEP" does not match "TELEPORT".
fn contains_token(normalised: &str, token: &str) -> bool {
    if token.contains(' ') || token.contains('_') {
        return normalised.contains(token);
    }
    let chars: Vec<char> = normalised.chars().collect();
    let token: Vec<char> = token.chars().collect();
    (0..=chars.len().saturating_sub(token.len()))
        .filter(|&i| chars.len() >= token.len() && chars[i..i + token.len()] == token[..])
        .any(|i| {
            let before = i == 0 || !chars[i - 1].is_alphanumeric();
            let after = i + token.len() >= chars.len() || !chars[i + token.len()].is_alphanumeric();
            before && after
        })
}

/// Rules from the most specific to the generic livret.
pub fn suggest(account_name: Option<&str>) -> Option<Suggestion> {
    let name = account_name.filter(|n| !n.trim().is_empty())?;
    let n = normalise(name);
    let certain = |product, rate: &str| {
        Some(Suggestion {
            product,
            default_annual_rate: Some(rate.parse().expect("a rate literal")),
            uncertain: false,
        })
    };
    if contains_token(&n, "LIVRET A") || contains_token(&n, "LIVRET_A") {
        return certain(SavingsProduct::LivretA, RegulatedRates::LIVRET_A);
    }
    if contains_token(&n, "LDDS")
        || contains_token(&n, "LDD")
        || n.contains("DEVELOPPEMENT DURABLE")
    {
        return certain(SavingsProduct::Ldds, RegulatedRates::LDDS);
    }
    if contains_token(&n, "LEP") || n.contains("EPARGNE POPULAIRE") {
        return certain(SavingsProduct::Lep, RegulatedRates::LEP);
    }
    if n.contains("LIVRET") || contains_token(&n, "CSL") || n.contains("COMPTE SUR LIVRET") {
        return Some(Suggestion {
            product: SavingsProduct::Commercial,
            default_annual_rate: None,
            uncertain: true,
        });
    }
    None
}

#[cfg(test)]
mod tests {
    use super::*;

    fn product(name: &str) -> Option<(SavingsProduct, bool)> {
        suggest(Some(name)).map(|s| (s.product, s.uncertain))
    }

    #[test]
    fn regulated_books_are_certain() {
        for name in ["Livret A", "LIVRET A", "livret a", "Mon Livret A BNP"] {
            assert_eq!(
                product(name),
                Some((SavingsProduct::LivretA, false)),
                "{name}"
            );
        }
        for name in [
            "LDDS",
            "LDD",
            "Livret Développement Durable et Solidaire",
            "Livret developpement durable",
            "LDD Crédit Agricole",
        ] {
            assert_eq!(product(name), Some((SavingsProduct::Ldds, false)), "{name}");
        }
        for name in [
            "LEP",
            "Livret Épargne Populaire",
            "Livret epargne populaire",
        ] {
            assert_eq!(product(name), Some((SavingsProduct::Lep, false)), "{name}");
        }
    }

    #[test]
    fn a_generic_livret_is_an_uncertain_commercial_book() {
        for name in [
            "Livret",
            "Mon livret",
            "CSL",
            "Compte sur livret",
            "COMPTE SUR LIVRET",
        ] {
            let s = suggest(Some(name)).expect(name);
            assert_eq!(s.product, SavingsProduct::Commercial);
            assert!(s.uncertain);
            assert_eq!(s.default_annual_rate, None);
        }
    }

    #[test]
    fn other_names_and_embedded_tokens_suggest_nothing() {
        for name in [
            "Compte courant",
            "Compte chèques",
            "PEA",
            "Assurance-vie",
            "Crypto BTC",
            "",
            "  ",
        ] {
            assert_eq!(product(name), None, "{name}");
        }
        assert_eq!(suggest(None), None);
        assert_eq!(product("TELEPORT"), None);
        assert_eq!(product("CSLX account"), None);
    }

    #[test]
    fn rates_keep_their_scale() {
        assert_eq!(
            suggest(Some("Livret A"))
                .unwrap()
                .default_annual_rate
                .unwrap()
                .to_string(),
            "2.40"
        );
        assert_eq!(
            suggest(Some("LEP"))
                .unwrap()
                .default_annual_rate
                .unwrap()
                .to_string(),
            "3.50"
        );
    }
}
