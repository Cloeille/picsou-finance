//! The bytes Jackson's streaming generator writes with DefaultPrettyPrinter: two-space object
//! indentation, " : " between a name and its value, arrays on one line ("[ {", "}, {", " ]"),
//! and Java's BigDecimal.toString and Instant.toString for numbers and timestamps.

use std::io::{self, Write};

use chrono::{DateTime, NaiveDate, Timelike, Utc};
use rust_decimal::Decimal;

/// One exported value, rendered as Java renders it in data.json and in the CSV files.
#[derive(Debug, Clone, PartialEq)]
pub enum Cell {
    Null,
    Text(String),
    Int(i64),
    Bool(bool),
    Dec(Decimal),
    Date(NaiveDate),
    Instant(DateTime<Utc>),
}

impl Cell {
    /// The CSV field: empty for null, BigDecimal.toPlainString for numbers.
    pub fn csv(&self) -> String {
        match self {
            Self::Null => String::new(),
            Self::Text(s) => s.clone(),
            Self::Int(n) => n.to_string(),
            Self::Bool(b) => b.to_string(),
            Self::Dec(d) => d.to_string(),
            Self::Date(d) => d.to_string(),
            Self::Instant(t) => java_instant(*t),
        }
    }
}

/// Instant.toString: ISO-8601 in UTC, seconds always, the fraction in groups of three digits.
pub fn java_instant(at: DateTime<Utc>) -> String {
    let nanos = at.nanosecond() % 1_000_000_000;
    let base = at.format("%Y-%m-%dT%H:%M:%S");
    if nanos == 0 {
        format!("{base}Z")
    } else if nanos.is_multiple_of(1_000_000) {
        format!("{base}.{:03}Z", nanos / 1_000_000)
    } else if nanos.is_multiple_of(1_000) {
        format!("{base}.{:06}Z", nanos / 1_000)
    } else {
        format!("{base}.{nanos:09}Z")
    }
}

/// BigDecimal.toString, which Jackson writes for a BigDecimal: plain unless the adjusted
/// exponent falls below -6 (0E-8 for a zero of scale 8, 1.0E-7 for 0.00000010).
pub fn java_big_decimal(value: &Decimal) -> String {
    let scale = i64::from(value.scale());
    let digits = value.mantissa().unsigned_abs().to_string();
    let sign = if value.mantissa() < 0 { "-" } else { "" };
    let adjusted = (digits.len() as i64 - 1) - scale;
    if scale == 0 || adjusted >= -6 {
        return value.to_string();
    }
    let (first, rest) = digits.split_at(1);
    if rest.is_empty() {
        format!("{sign}{first}E{adjusted}")
    } else {
        format!("{sign}{first}.{rest}E{adjusted}")
    }
}

struct Level {
    object: bool,
    entries: usize,
}

/// A streaming generator over any writer; the caller keeps the nesting balanced.
#[derive(Default)]
pub struct JsonWriter {
    levels: Vec<Level>,
}

impl JsonWriter {
    fn object_nesting(&self) -> usize {
        self.levels.iter().filter(|l| l.object).count()
    }

    fn indent(out: &mut impl Write, nesting: usize) -> io::Result<()> {
        out.write_all(b"\n")?;
        for _ in 0..nesting {
            out.write_all(b"  ")?;
        }
        Ok(())
    }

    fn before_value(&mut self, out: &mut impl Write) -> io::Result<()> {
        if let Some(level) = self.levels.last_mut()
            && !level.object
        {
            out.write_all(if level.entries == 0 { b" " } else { b", " })?;
            level.entries += 1;
        }
        Ok(())
    }

    pub fn field(&mut self, out: &mut impl Write, name: &str) -> io::Result<()> {
        let nesting = self.object_nesting();
        let level = self
            .levels
            .last_mut()
            .filter(|l| l.object)
            .ok_or_else(|| io::Error::other("field name outside an object"))?;
        if level.entries > 0 {
            out.write_all(b",")?;
        }
        level.entries += 1;
        Self::indent(out, nesting)?;
        write_string(out, name)?;
        out.write_all(b" : ")
    }

    pub fn start_object(&mut self, out: &mut impl Write) -> io::Result<()> {
        self.before_value(out)?;
        out.write_all(b"{")?;
        self.levels.push(Level {
            object: true,
            entries: 0,
        });
        Ok(())
    }

    pub fn end_object(&mut self, out: &mut impl Write) -> io::Result<()> {
        let level = self.levels.pop().filter(|l| l.object);
        let entries = level.map_or(0, |l| l.entries);
        if entries > 0 {
            Self::indent(out, self.object_nesting())?;
        } else {
            out.write_all(b" ")?;
        }
        out.write_all(b"}")
    }

    pub fn start_array(&mut self, out: &mut impl Write) -> io::Result<()> {
        self.before_value(out)?;
        out.write_all(b"[")?;
        self.levels.push(Level {
            object: false,
            entries: 0,
        });
        Ok(())
    }

    pub fn end_array(&mut self, out: &mut impl Write) -> io::Result<()> {
        self.levels.pop();
        out.write_all(b" ]")
    }

    pub fn value(&mut self, out: &mut impl Write, cell: &Cell) -> io::Result<()> {
        self.before_value(out)?;
        match cell {
            Cell::Null => out.write_all(b"null"),
            Cell::Text(s) => write_string(out, s),
            Cell::Int(n) => write!(out, "{n}"),
            Cell::Bool(b) => write!(out, "{b}"),
            Cell::Dec(d) => out.write_all(java_big_decimal(d).as_bytes()),
            Cell::Date(d) => write_string(out, &d.to_string()),
            Cell::Instant(t) => write_string(out, &java_instant(*t)),
        }
    }

    pub fn entry(&mut self, out: &mut impl Write, name: &str, cell: &Cell) -> io::Result<()> {
        self.field(out, name)?;
        self.value(out, cell)
    }

    /// One flat row as an object, keyed by the column names.
    pub fn row(&mut self, out: &mut impl Write, names: &[&str], cells: &[Cell]) -> io::Result<()> {
        self.start_object(out)?;
        for (name, cell) in names.iter().zip(cells) {
            self.entry(out, name, cell)?;
        }
        self.end_object(out)
    }
}

/// Jackson's escaping: quote, backslash and control characters only, with the short forms for
/// \b \t \n \f \r and upper-case hex for the rest.
fn write_string(out: &mut impl Write, s: &str) -> io::Result<()> {
    let mut escaped = String::with_capacity(s.len() + 2);
    escaped.push('"');
    for c in s.chars() {
        match c {
            '"' => escaped.push_str("\\\""),
            '\\' => escaped.push_str("\\\\"),
            '\u{08}' => escaped.push_str("\\b"),
            '\t' => escaped.push_str("\\t"),
            '\n' => escaped.push_str("\\n"),
            '\u{0C}' => escaped.push_str("\\f"),
            '\r' => escaped.push_str("\\r"),
            c if u32::from(c) < 0x20 => escaped.push_str(&format!("\\u{:04X}", u32::from(c))),
            c => escaped.push(c),
        }
    }
    escaped.push('"');
    out.write_all(escaped.as_bytes())
}

#[cfg(test)]
mod tests {
    use std::str::FromStr;

    use super::*;

    fn dec(s: &str) -> Decimal {
        Decimal::from_str(s).unwrap()
    }

    #[test]
    fn big_decimal_to_string_switches_to_scientific_below_minus_six() {
        assert_eq!(java_big_decimal(&dec("0.00000000")), "0E-8");
        assert_eq!(java_big_decimal(&dec("0.00000010")), "1.0E-7");
        assert_eq!(java_big_decimal(&dec("0.00000100")), "0.00000100");
        assert_eq!(java_big_decimal(&dec("-750.00000000")), "-750.00000000");
        assert_eq!(java_big_decimal(&dec("-0.00000012")), "-1.2E-7");
        assert_eq!(java_big_decimal(&dec("1000")), "1000");
        assert_eq!(java_big_decimal(&dec("0")), "0");
    }

    #[test]
    fn instant_to_string_keeps_the_fraction_in_groups_of_three() {
        let at = |s: &str| DateTime::parse_from_rfc3339(s).unwrap().with_timezone(&Utc);
        assert_eq!(
            java_instant(at("2020-01-01T00:00:00Z")),
            "2020-01-01T00:00:00Z"
        );
        assert_eq!(
            java_instant(at("2020-01-02T00:00:00.120Z")),
            "2020-01-02T00:00:00.120Z"
        );
        assert_eq!(
            java_instant(at("2020-01-01T00:00:00.123456Z")),
            "2020-01-01T00:00:00.123456Z"
        );
        assert_eq!(
            java_instant(at("2020-01-01T00:00:00.000001Z")),
            "2020-01-01T00:00:00.000001Z"
        );
    }

    #[test]
    fn pretty_prints_like_default_pretty_printer() {
        let mut out = Vec::new();
        let mut j = JsonWriter::default();
        j.start_object(&mut out).unwrap();
        j.entry(&mut out, "a", &Cell::Text("x\"\n\u{1}".into()))
            .unwrap();
        j.field(&mut out, "list").unwrap();
        j.start_array(&mut out).unwrap();
        j.row(&mut out, &["id"], &[Cell::Int(1)]).unwrap();
        j.row(&mut out, &["id"], &[Cell::Int(2)]).unwrap();
        j.end_array(&mut out).unwrap();
        j.field(&mut out, "empty").unwrap();
        j.start_array(&mut out).unwrap();
        j.end_array(&mut out).unwrap();
        j.field(&mut out, "ids").unwrap();
        j.start_array(&mut out).unwrap();
        j.value(&mut out, &Cell::Int(3)).unwrap();
        j.value(&mut out, &Cell::Int(4)).unwrap();
        j.end_array(&mut out).unwrap();
        j.field(&mut out, "obj").unwrap();
        j.start_object(&mut out).unwrap();
        j.end_object(&mut out).unwrap();
        j.end_object(&mut out).unwrap();
        assert_eq!(
            String::from_utf8(out).unwrap(),
            "{\n  \"a\" : \"x\\\"\\n\\u0001\",\n  \"list\" : [ {\n    \"id\" : 1\n  }, {\n    \"id\" : 2\n  } ],\n  \"empty\" : [ ],\n  \"ids\" : [ 3, 4 ],\n  \"obj\" : { }\n}"
        );
    }
}
