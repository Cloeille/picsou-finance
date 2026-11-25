//! The one conversion an XLSX file forces: a numeric cell is an IEEE double, as POI's
//! setCellValue(BigDecimal.doubleValue()) writes it. Nothing reads the value back.

use rust_decimal::Decimal;
use rust_decimal::prelude::ToPrimitive;

pub fn number(value: Decimal) -> f64 {
    value.to_f64().unwrap_or_default()
}

pub fn integer(value: i64) -> f64 {
    value.to_f64().unwrap_or_default()
}
