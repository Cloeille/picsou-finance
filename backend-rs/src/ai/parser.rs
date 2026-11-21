//! Response text cleaning and structured JSON parsing for AI categorization answers,
//! matching Spring AI's BeanOutputConverter (ThinkingTagCleaner + MarkdownCodeBlockCleaner)
//! and SpringAiCategorizer.toSuggestion.

use std::str::FromStr;
use std::sync::LazyLock;

use regex::Regex;
use rust_decimal::Decimal;
use serde_json::Value;

use crate::budget::ai_job::CategorySuggestion;

/// Patterns from Spring AI's ThinkingTagCleaner.DEFAULT_PATTERNS.
static THINKING_PATTERNS: LazyLock<Vec<Regex>> = LazyLock::new(|| {
    [
        r"(?is)<thinking>.*?</thinking>\s*",
        r"(?is)<think>.*?</think>\s*",
        r"(?is)<reasoning>.*?</reasoning>\s*",
        r"(?is)```thinking.*?```\s*",
        r"(?is)<!--\s*thinking:.*?-->\s*",
    ]
    .into_iter()
    .map(|p| Regex::new(p).expect("valid thinking-tag regex"))
    .collect()
});

/// Strips thinking tags, outer markdown code fences, and surrounding whitespace.
pub(crate) fn clean_response_text(raw: &str) -> String {
    let mut text = raw.trim().to_owned();
    if text.contains('<') || text.contains('`') {
        for pattern in THINKING_PATTERNS.iter() {
            let cleaned = pattern.replace_all(&text, "");
            if cleaned != text {
                text = cleaned.into_owned();
            }
        }
    }
    text = strip_markdown_code_block(&text);
    text.trim().to_owned()
}

fn strip_markdown_code_block(input: &str) -> String {
    let trimmed = input.trim();
    if trimmed.len() >= 6 && trimmed.starts_with("```") && trimmed.ends_with("```") {
        let mut inner = match trimmed.split_once('\n') {
            Some((first, rest)) => {
                if first.trim().len() > 3 {
                    rest
                } else {
                    &trimmed[3..]
                }
            }
            None => {
                if trimmed.len() > 3 {
                    ""
                } else {
                    &trimmed[3..]
                }
            }
        };
        if let Some(stripped) = inner.strip_suffix("```") {
            inner = stripped;
        }
        return inner.trim().to_owned();
    }
    trimmed.to_owned()
}

fn parse_decimal(raw: &str) -> Result<Decimal, String> {
    Decimal::from_str(raw)
        .or_else(|_| Decimal::from_scientific(raw))
        .map_err(|e| format!("Invalid confidence number '{raw}': {e}"))
}

/// Parses the model's text answer into an optional category suggestion.
///
/// Returns `Ok(None)` when the model abstains (`categorySlug` is empty, null, or `"unknown"`),
/// `Ok(Some(...))` with normalized slug and confidence clamped to `[0, 1]`, or `Err` on
/// malformed JSON or wrong field types.
pub(crate) fn parse_answer(raw: &str) -> Result<Option<CategorySuggestion>, String> {
    let cleaned = clean_response_text(raw);
    let value: Value =
        serde_json::from_str(&cleaned).map_err(|e| format!("Failed to parse model answer: {e}"))?;
    let Some(map) = value.as_object() else {
        return Err("Failed to parse model answer: expected a JSON object".to_owned());
    };

    let raw_slug = match map.get("categorySlug") {
        None | Some(Value::Null) => None,
        Some(Value::String(s)) => Some(s.as_str()),
        Some(_) => {
            return Err(
                "Failed to parse model answer: categorySlug must be a string or null".to_owned(),
            );
        }
    };

    let raw_confidence = match map.get("confidence") {
        None | Some(Value::Null) => Decimal::ZERO,
        Some(Value::Number(n)) => parse_decimal(n.as_str())?,
        Some(Value::String(s)) => parse_decimal(s.trim())?,
        Some(_) => {
            return Err(
                "Failed to parse model answer: confidence must be a number or null".to_owned(),
            );
        }
    };

    let slug = raw_slug.unwrap_or("").trim().to_lowercase();
    if slug.is_empty() || slug == "unknown" {
        return Ok(None);
    }

    Ok(Some(CategorySuggestion {
        category_slug: slug,
        confidence: raw_confidence.clamp(Decimal::ZERO, Decimal::ONE),
    }))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn dec(s: &str) -> Decimal {
        Decimal::from_str(s).unwrap()
    }

    #[test]
    fn parses_plain_and_fenced_json() {
        let plain = parse_answer(r#"{"categorySlug": "groceries", "confidence": 0.9}"#).unwrap();
        assert_eq!(
            plain,
            Some(CategorySuggestion {
                category_slug: "groceries".to_owned(),
                confidence: dec("0.9"),
            })
        );

        let fenced =
            parse_answer("```json\n{\"categorySlug\": \"Groceries \", \"confidence\": 0.7}\n```")
                .unwrap();
        assert_eq!(
            fenced,
            Some(CategorySuggestion {
                category_slug: "groceries".to_owned(),
                confidence: dec("0.7"),
            })
        );

        let bare_fence =
            parse_answer("```\n{\"categorySlug\": \"transport\", \"confidence\": 0.85}\n```")
                .unwrap();
        assert_eq!(
            bare_fence,
            Some(CategorySuggestion {
                category_slug: "transport".to_owned(),
                confidence: dec("0.85"),
            })
        );
    }

    #[test]
    fn strips_thinking_tags_before_parsing() {
        let with_think = "<think>Looks like a supermarket</think>\n\
                          ```json\n{\"categorySlug\": \"groceries\", \"confidence\": 0.92}\n```";
        let sug = parse_answer(with_think).unwrap().unwrap();
        assert_eq!(sug.category_slug, "groceries");
        assert_eq!(sug.confidence, dec("0.92"));

        let with_thinking_block = "<THINKING>hmm\nmulti-line</THINKING> {\"categorySlug\": \"dining\", \"confidence\": 0.6}";
        let sug = parse_answer(with_thinking_block).unwrap().unwrap();
        assert_eq!(sug.category_slug, "dining");
        assert_eq!(sug.confidence, dec("0.6"));
    }

    #[test]
    fn unknown_and_blank_slugs_yield_none() {
        assert_eq!(
            parse_answer(r#"{"categorySlug": "unknown", "confidence": 0.2}"#).unwrap(),
            None
        );
        assert_eq!(
            parse_answer(r#"{"categorySlug": " UNKNOWN ", "confidence": 0.1}"#).unwrap(),
            None
        );
        assert_eq!(
            parse_answer(r#"{"categorySlug": "", "confidence": 0.5}"#).unwrap(),
            None
        );
        assert_eq!(
            parse_answer(r#"{"categorySlug": null, "confidence": 0.5}"#).unwrap(),
            None
        );
    }

    #[test]
    fn clamps_confidence_and_handles_scientific_and_missing_values() {
        let high = parse_answer(r#"{"categorySlug": "groceries", "confidence": 1.5}"#)
            .unwrap()
            .unwrap();
        assert_eq!(high.confidence, Decimal::ONE);

        let low = parse_answer(r#"{"categorySlug": "groceries", "confidence": -0.25}"#)
            .unwrap()
            .unwrap();
        assert_eq!(low.confidence, Decimal::ZERO);

        let sci = parse_answer(r#"{"categorySlug": "groceries", "confidence": 9e-1}"#)
            .unwrap()
            .unwrap();
        assert_eq!(sci.confidence, dec("0.9"));

        let missing = parse_answer(r#"{"categorySlug": "groceries"}"#)
            .unwrap()
            .unwrap();
        assert_eq!(missing.confidence, Decimal::ZERO);

        let extra_fields =
            parse_answer(r#"{"categorySlug": "groceries", "confidence": 0.8, "reason": "ok"}"#)
                .unwrap()
                .unwrap();
        assert_eq!(extra_fields.category_slug, "groceries");
    }

    #[test]
    fn rejects_malformed_json_and_wrong_types() {
        assert!(parse_answer("not json at all").is_err());
        assert!(parse_answer("[]").is_err());
        assert!(parse_answer(r#"{"categorySlug": "groceries", "confidence": "high"}"#).is_err());
        assert!(parse_answer(r#"{"categorySlug": 123, "confidence": 0.5}"#).is_err());
    }
}
