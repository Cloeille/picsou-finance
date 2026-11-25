//! java.util.HashMap iteration order for String keys. Java sorts some lists with a stable sort
//! over a HashMap's entries, so ties come out in that order; reproducing it keeps the JSON equal.

/// String.hashCode over UTF-16 code units.
fn string_hash(s: &str) -> i32 {
    s.encode_utf16()
        .fold(0i32, |h, c| h.wrapping_mul(31).wrapping_add(i32::from(c)))
}

/// The table size a default HashMap has after n insertions (resized past 0.75 load).
fn capacity(n: usize) -> usize {
    let mut cap = 16;
    while n * 4 > cap * 3 {
        cap *= 2;
    }
    cap
}

/// Entries given in insertion order, returned in the order a HashMap holding them iterates.
pub fn hash_order<T>(entries: Vec<(String, T)>) -> Vec<(String, T)> {
    let mask = capacity(entries.len()) as u32 - 1;
    let mut keyed: Vec<(u32, (String, T))> = entries
        .into_iter()
        .map(|e| {
            let h = string_hash(&e.0) as u32;
            ((h ^ (h >> 16)) & mask, e)
        })
        .collect();
    keyed.sort_by_key(|(bucket, _)| *bucket);
    keyed.into_iter().map(|(_, e)| e).collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn follows_java_bucket_order() {
        assert_eq!(string_hash("technology"), -1_679_325_940);
        // Java: new HashMap<>() with "us", "fr", "de" put in that order iterates de, fr, us.
        let order: Vec<String> = hash_order(vec![
            ("us".to_owned(), ()),
            ("fr".to_owned(), ()),
            ("de".to_owned(), ()),
        ])
        .into_iter()
        .map(|(k, _)| k)
        .collect();
        assert_eq!(order, ["de", "fr", "us"]);
    }

    #[test]
    fn follows_java_order_across_a_resize() {
        let keys = [
            "technology",
            "healthcare",
            "financial_services",
            "industrials",
            "consumer_cyclical",
            "energy",
            "utilities",
            "real_estate",
            "basic_materials",
            "communication_services",
            "consumer_defensive",
            "US",
            "FR",
            "DE",
            "JP",
            "GB",
            "CN",
            "IE",
            "NL",
            "CH",
            "XU",
            "private_equity",
        ];
        let order: Vec<String> = hash_order(keys.iter().map(|k| ((*k).to_owned(), ())).collect())
            .into_iter()
            .map(|(k, _)| k)
            .collect();
        assert_eq!(
            order,
            [
                "consumer_cyclical",
                "DE",
                "CH",
                "JP",
                "technology",
                "communication_services",
                "CN",
                "utilities",
                "FR",
                "private_equity",
                "industrials",
                "healthcare",
                "basic_materials",
                "consumer_defensive",
                "financial_services",
                "GB",
                "IE",
                "XU",
                "US",
                "NL",
                "energy",
                "real_estate",
            ]
        );
    }
}
