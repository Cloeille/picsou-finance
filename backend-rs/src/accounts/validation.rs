//! Bean validation of the T2 request bodies. Constraints the foundation's Errors has no helper
//! for (DecimalMin, Min/Max, PastOrPresent, Digits) carry Hibernate Validator's default messages
//! here; PastOrPresent needs the clock, hence an extractor over AppState.

use axum::extract::{FromRequest, Request};
use axum::http::header;
use chrono::NaiveDate;
use rust_decimal::Decimal;
use serde::de::DeserializeOwned;

use crate::AppState;
use crate::error::AppError;
use crate::http::json::{Errors, Lang, read_json};

pub struct Check<'a> {
    pub errors: &'a mut Errors,
    pub lang: Lang,
    pub today: NaiveDate,
}

impl Check<'_> {
    fn message(&self, english: String, french: String) -> String {
        match self.lang {
            Lang::English => english,
            Lang::French => french,
        }
    }

    /// @DecimalMin(value), inclusive unless stated; null is valid.
    pub fn decimal_min(
        &mut self,
        field: &str,
        value: Option<Decimal>,
        min: &str,
        inclusive: bool,
    ) -> &mut Self {
        let bound: Decimal = min.parse().expect("a decimal bound");
        if let Some(v) = value {
            let ok = if inclusive { v >= bound } else { v > bound };
            if !ok {
                let message = if inclusive {
                    self.message(
                        format!("must be greater than or equal to {min}"),
                        format!("doit être supérieur ou égal à {min}"),
                    )
                } else {
                    self.message(
                        format!("must be greater than {min}"),
                        format!("doit être supérieur à {min}"),
                    )
                };
                self.errors.add(field, message);
            }
        }
        self
    }

    /// @DecimalMax(value), inclusive; null is valid.
    pub fn decimal_max(&mut self, field: &str, value: Option<Decimal>, max: &str) -> &mut Self {
        let bound: Decimal = max.parse().expect("a decimal bound");
        if let Some(v) = value
            && v > bound
        {
            let message = self.message(
                format!("must be less than or equal to {max}"),
                format!("doit être inférieur ou égal à {max}"),
            );
            self.errors.add(field, message);
        }
        self
    }

    /// @Min and @Max on an integer; null is valid.
    pub fn range(&mut self, field: &str, value: Option<i64>, min: i64, max: i64) -> &mut Self {
        if let Some(v) = value {
            if v < min {
                let message = self.message(
                    format!("must be greater than or equal to {min}"),
                    format!("doit être supérieur ou égal à {min}"),
                );
                self.errors.add(field, message);
            } else if v > max {
                let message = self.message(
                    format!("must be less than or equal to {max}"),
                    format!("doit être inférieur ou égal à {max}"),
                );
                self.errors.add(field, message);
            }
        }
        self
    }

    /// @PastOrPresent on a LocalDate; null is valid.
    pub fn past_or_present(&mut self, field: &str, value: Option<NaiveDate>) -> &mut Self {
        if let Some(v) = value
            && v > self.today
        {
            let message = self.message(
                "must be a date in the past or in the present".to_owned(),
                "doit être une date dans le passé ou dans le présent".to_owned(),
            );
            self.errors.add(field, message);
        }
        self
    }

    /// @Digits(integer, fraction); null is valid.
    pub fn digits(
        &mut self,
        field: &str,
        value: Option<Decimal>,
        integer: u32,
        fraction: u32,
    ) -> &mut Self {
        if let Some(v) = value {
            let n = v.normalize();
            let scale = n.scale();
            let int_digits = n.trunc().abs().to_string().trim_start_matches('0').len() as u32;
            if scale > fraction || int_digits > integer {
                let message = self.message(
                    format!("numeric value out of bounds (<{integer} digits>.<{fraction} digits> expected)"),
                    format!("valeur numérique hors limites (<{integer} chiffres>.<{fraction} chiffres> attendus)"),
                );
                self.errors.add(field, message);
            }
        }
        self
    }
}

/// Validation that may need the request language and today's date.
pub trait ValidateAt {
    fn validate_at(&self, check: &mut Check<'_>);
}

/// A JSON body validated with ValidateAt.
pub struct ValidJson<T>(pub T);

impl<T> FromRequest<AppState> for ValidJson<T>
where
    T: DeserializeOwned + ValidateAt,
{
    type Rejection = AppError;

    async fn from_request(request: Request, state: &AppState) -> Result<Self, Self::Rejection> {
        let lang = Lang::from_accept_language(
            request
                .headers()
                .get(header::ACCEPT_LANGUAGE)
                .and_then(|v| v.to_str().ok()),
        );
        let value: T = read_json(request, state).await?;
        let mut errors = Errors::new(lang);
        value.validate_at(&mut Check {
            errors: &mut errors,
            lang,
            today: state.clock.today(),
        });
        errors.into_result()?;
        Ok(Self(value))
    }
}
