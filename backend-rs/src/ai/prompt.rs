//! Prompts for AI transaction categorization, matching Java's SpringAiCategorizer and
//! Spring AI's BeanOutputConverter<Answer>.

use std::fmt::Write;

use crate::budget::ai_job::{CategorizationInput, CategoryOption, Example};

pub(crate) const SYSTEM_PROMPT: &str = "\
You categorize a single bank transaction for a personal-finance app.
Choose exactly ONE category from the provided list, using only the given category slugs.
Decide from the merchant name, description, and direction (expense vs income).
Map digital/SaaS/AI/cloud/gaming subscriptions to \"abonnements\" when listed, and card top-ups or transfers between own accounts to \"virement-interne\" when listed.
Only use \"unknown\" when the merchant and description are genuinely opaque (for example a bare card number or person's name with no context).
Return the chosen slug and a confidence between 0 and 1.";

pub(crate) const JSON_FORMAT_INSTRUCTIONS: &str = "\
Your response should be in JSON format.
Do not include any explanations, only provide a RFC8259 compliant JSON response following this format without deviation.
Do not include markdown code blocks in your response.
Remove the ```json markdown from the output.
Here is the JSON Schema instance your output must adhere to:
```{
  \"$schema\" : \"https://json-schema.org/draft/2020-12/schema\",
  \"type\" : \"object\",
  \"properties\" : {
    \"categorySlug\" : {
      \"type\" : \"string\"
    },
    \"confidence\" : {
      \"type\" : \"number\"
    }
  },
  \"required\" : [ \"categorySlug\", \"confidence\" ],
  \"additionalProperties\" : false
}```
";

pub(crate) fn build_user_prompt(
    input: &CategorizationInput,
    categories: &[CategoryOption],
    examples: &[Example],
) -> String {
    let mut sb = String::new();
    sb.push_str("Categories (slug = name):\n");
    for c in categories {
        let _ = writeln!(sb, "- {} = {}", c.slug, c.name);
    }
    if !examples.is_empty() {
        sb.push_str("\nThis member's past choices (merchant => slug):\n");
        for e in examples {
            let label = e.merchant_label.as_deref().unwrap_or("");
            let _ = writeln!(sb, "- \"{label}\" => {}", e.category_slug);
        }
    }
    sb.push_str("\nTransaction to categorize:\n");
    let _ = writeln!(sb, "- merchant: \"{}\"", input.merchant_label);
    let _ = writeln!(sb, "- description: \"{}\"", input.description);
    let _ = writeln!(sb, "- amount: {} EUR", input.amount);
    let direction = if input.amount.is_sign_negative() {
        "expense"
    } else {
        "income"
    };
    let _ = writeln!(sb, "- direction: {direction}");
    sb.push_str("\nReply with the single best category slug from the list above (or \"unknown\").");
    sb
}

/// Returns `(user_prompt_with_format, full_audit_prompt)` matching SpringAiCategorizer.
pub(crate) fn build_prompts(
    input: &CategorizationInput,
    categories: &[CategoryOption],
    examples: &[Example],
) -> (String, String) {
    let base = build_user_prompt(input, categories, examples);
    let user_prompt = format!("{base}\n{JSON_FORMAT_INSTRUCTIONS}");
    let full_prompt = format!("{SYSTEM_PROMPT}\n\n{user_prompt}");
    (user_prompt, full_prompt)
}

#[cfg(test)]
mod tests {
    use std::str::FromStr;

    use rust_decimal::Decimal;

    use super::*;

    #[test]
    fn builds_user_and_full_prompt_matching_java() {
        let input = CategorizationInput {
            merchant_label: "CARREFOUR".to_owned(),
            description: "CB CARREFOUR".to_owned(),
            amount: Decimal::from_str("-42.10").unwrap(),
        };
        let categories = vec![
            CategoryOption {
                slug: "groceries".to_owned(),
                name: "Courses".to_owned(),
            },
            CategoryOption {
                slug: "transport".to_owned(),
                name: "Transport".to_owned(),
            },
        ];
        let examples = vec![Example {
            merchant_label: Some("Monoprix".to_owned()),
            category_slug: "groceries".to_owned(),
        }];

        let (user, full) = build_prompts(&input, &categories, &examples);

        assert!(user.starts_with(
            "Categories (slug = name):\n\
             - groceries = Courses\n\
             - transport = Transport\n\
             \n\
             This member's past choices (merchant => slug):\n\
             - \"Monoprix\" => groceries\n\
             \n\
             Transaction to categorize:\n\
             - merchant: \"CARREFOUR\"\n\
             - description: \"CB CARREFOUR\"\n\
             - amount: -42.10 EUR\n\
             - direction: expense\n\
             \n\
             Reply with the single best category slug from the list above (or \"unknown\").\n"
        ));
        assert!(user.contains(JSON_FORMAT_INSTRUCTIONS));
        assert_eq!(full, format!("{SYSTEM_PROMPT}\n\n{user}"));
    }

    #[test]
    fn omits_examples_section_when_empty() {
        let input = CategorizationInput {
            merchant_label: "SNCF".to_owned(),
            description: "SNCF CONNECT".to_owned(),
            amount: Decimal::from_str("-18.50").unwrap(),
        };
        let categories = vec![CategoryOption {
            slug: "transport".to_owned(),
            name: "Transport".to_owned(),
        }];

        let (user, _) = build_prompts(&input, &categories, &[]);
        assert!(!user.contains("past choices"));
        assert!(user.contains("- merchant: \"SNCF\""));
    }
}
