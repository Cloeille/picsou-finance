use std::path::Path;

/// Files allowed to mention a binary float type, with the reason. Money, quantities, prices and
/// rates are rust_decimal::Decimal end to end.
const ALLOWED: &[&str] = &[];

fn scan(dir: &Path, hits: &mut Vec<String>) {
    let float = regex::Regex::new(r"\bf(32|64)\b").unwrap();
    for entry in std::fs::read_dir(dir).unwrap() {
        let path = entry.unwrap().path();
        if path.is_dir() {
            scan(&path, hits);
        } else if path.extension().is_some_and(|e| e == "rs") {
            let relative = path
                .strip_prefix(env!("CARGO_MANIFEST_DIR"))
                .unwrap()
                .to_string_lossy()
                .into_owned();
            if ALLOWED.contains(&relative.as_str()) {
                continue;
            }
            let text = std::fs::read_to_string(&path).unwrap();
            for (n, line) in text.lines().enumerate() {
                if float.is_match(line) {
                    hits.push(format!("{relative}:{}: {}", n + 1, line.trim()));
                }
            }
        }
    }
}

#[test]
fn no_binary_floats_in_src() {
    let mut hits = Vec::new();
    scan(
        &Path::new(env!("CARGO_MANIFEST_DIR")).join("src"),
        &mut hits,
    );
    assert!(
        hits.is_empty(),
        "f32/f64 outside the allow-list:\n{}",
        hits.join("\n")
    );
}
