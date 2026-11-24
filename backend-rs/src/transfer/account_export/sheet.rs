//! SheetCursor and WorkbookStyles: appends typed rows top to bottom over one worksheet.

use chrono::{DateTime, Datelike, NaiveDate, NaiveDateTime, Timelike, Utc};
use chrono_tz::Tz;
use rust_decimal::Decimal;
use rust_xlsxwriter::{ExcelDateTime, Format, FormatAlign, Worksheet, XlsxError};

use super::cell;

pub struct Styles {
    pub title: Format,
    pub header: Format,
    pub field_name: Format,
    pub text: Format,
    pub money: Format,
    pub quantity: Format,
    /// For a figure already expressed out of 100, never Excel's 0.00% which rescales.
    pub percent: Format,
    pub integer: Format,
    pub date: Format,
    pub date_time: Format,
}

impl Styles {
    pub fn new() -> Self {
        Self {
            title: Format::new().set_bold().set_font_size(12),
            header: Format::new().set_bold().set_align(FormatAlign::Left),
            field_name: Format::new().set_bold(),
            text: Format::new(),
            money: Format::new().set_num_format("#,##0.00"),
            quantity: Format::new().set_num_format("#,##0.########"),
            percent: Format::new().set_num_format("#,##0.00\" %\""),
            integer: Format::new().set_num_format("0"),
            date: Format::new().set_num_format("dd/mm/yyyy"),
            date_time: Format::new().set_num_format("dd/mm/yyyy hh:mm"),
        }
    }
}

/// A value of a label: value line, written as its runtime type calls for.
pub enum Value {
    None,
    Text(String),
    Money(Decimal),
    Integer(i64),
    Date(NaiveDate),
    Instant(DateTime<Utc>),
}

impl From<Option<String>> for Value {
    fn from(v: Option<String>) -> Self {
        v.map_or(Self::None, Self::Text)
    }
}

impl From<Option<&str>> for Value {
    fn from(v: Option<&str>) -> Self {
        v.map_or(Self::None, |s| Self::Text(s.to_owned()))
    }
}

impl From<Option<Decimal>> for Value {
    fn from(v: Option<Decimal>) -> Self {
        v.map_or(Self::None, Self::Money)
    }
}

impl From<Decimal> for Value {
    fn from(v: Decimal) -> Self {
        Self::Money(v)
    }
}

impl From<Option<i64>> for Value {
    fn from(v: Option<i64>) -> Self {
        v.map_or(Self::None, Self::Integer)
    }
}

impl From<Option<NaiveDate>> for Value {
    fn from(v: Option<NaiveDate>) -> Self {
        v.map_or(Self::None, Self::Date)
    }
}

impl From<Option<DateTime<Utc>>> for Value {
    fn from(v: Option<DateTime<Utc>>) -> Self {
        v.map_or(Self::None, Self::Instant)
    }
}

fn excel_date(d: NaiveDate) -> Option<ExcelDateTime> {
    let year = u16::try_from(d.year())
        .ok()
        .filter(|y| (1900..=9999).contains(y))?;
    let month = u8::try_from(d.month()).ok()?;
    let day = u8::try_from(d.day()).ok()?;
    ExcelDateTime::from_ymd(year, month, day).ok()
}

fn excel_date_time(t: NaiveDateTime) -> Option<ExcelDateTime> {
    let hour = u16::try_from(t.hour()).ok()?;
    let minute = u8::try_from(t.minute()).ok()?;
    excel_date(t.date())?.and_hms(hour, minute, t.second()).ok()
}

pub struct Cursor<'a> {
    sheet: &'a mut Worksheet,
    styles: &'a Styles,
    zone: Tz,
    row: u32,
}

impl<'a> Cursor<'a> {
    pub fn new(sheet: &'a mut Worksheet, styles: &'a Styles, zone: Tz) -> Self {
        Self {
            sheet,
            styles,
            zone,
            row: 0,
        }
    }

    pub fn title(&mut self, value: &str) -> Result<(), XlsxError> {
        self.sheet
            .write_string_with_format(self.row, 0, value, &self.styles.title)?;
        self.row += 1;
        Ok(())
    }

    /// One blank row between blocks, skipped at the top of a sheet.
    pub fn blank(&mut self) {
        if self.row > 0 {
            self.row += 1;
        }
    }

    pub fn header_row(&mut self, headers: &[&str]) -> Result<(), XlsxError> {
        for (i, h) in headers.iter().enumerate() {
            self.sheet.write_string_with_format(
                self.row,
                u16::try_from(i).unwrap_or(u16::MAX),
                *h,
                &self.styles.header,
            )?;
        }
        self.row += 1;
        Ok(())
    }

    pub fn row(&mut self) -> Row<'_, 'a> {
        let row = self.row;
        self.row += 1;
        Row {
            cursor: self,
            row,
            column: 0,
        }
    }

    /// A two-column label: value line.
    pub fn field(&mut self, label: &str, value: impl Into<Value>) -> Result<(), XlsxError> {
        let mut row = self.row();
        row.text_styled(Some(label), true)?;
        row.auto(value.into())
    }

    /// A label: value line whose value is already out of 100.
    pub fn field_percent(&mut self, label: &str, value: Option<Decimal>) -> Result<(), XlsxError> {
        let mut row = self.row();
        row.text_styled(Some(label), true)?;
        row.percent(value)
    }
}

pub struct Row<'c, 'a> {
    cursor: &'c mut Cursor<'a>,
    row: u32,
    column: u16,
}

impl Row<'_, '_> {
    fn next(&mut self) -> u16 {
        let c = self.column;
        self.column += 1;
        c
    }

    pub fn auto(&mut self, value: Value) -> Result<(), XlsxError> {
        match value {
            Value::None => {
                self.column += 1;
                Ok(())
            }
            Value::Text(s) => self.text(Some(&s)),
            Value::Money(d) => self.money(Some(d)),
            Value::Integer(i) => self.integer(Some(i)),
            Value::Date(d) => self.date(Some(d)),
            Value::Instant(t) => self.date_time(Some(t)),
        }
    }

    fn text_styled(&mut self, value: Option<&str>, bold: bool) -> Result<(), XlsxError> {
        let column = self.next();
        let styles = self.cursor.styles;
        let format = if bold {
            &styles.field_name
        } else {
            &styles.text
        };
        match value {
            Some(v) => self
                .cursor
                .sheet
                .write_string_with_format(self.row, column, v, format)?,
            None => self.cursor.sheet.write_blank(self.row, column, format)?,
        };
        Ok(())
    }

    pub fn text(&mut self, value: Option<&str>) -> Result<(), XlsxError> {
        self.text_styled(value, false)
    }

    fn number(&mut self, value: Option<Decimal>, format: &Format) -> Result<(), XlsxError> {
        let column = self.next();
        match value {
            Some(v) => self.cursor.sheet.write_number_with_format(
                self.row,
                column,
                cell::number(v),
                format,
            )?,
            None => self.cursor.sheet.write_blank(self.row, column, format)?,
        };
        Ok(())
    }

    pub fn money(&mut self, value: Option<Decimal>) -> Result<(), XlsxError> {
        let styles = self.cursor.styles;
        self.number(value, &styles.money)
    }

    pub fn quantity(&mut self, value: Option<Decimal>) -> Result<(), XlsxError> {
        let styles = self.cursor.styles;
        self.number(value, &styles.quantity)
    }

    pub fn percent(&mut self, value: Option<Decimal>) -> Result<(), XlsxError> {
        let styles = self.cursor.styles;
        self.number(value, &styles.percent)
    }

    pub fn integer(&mut self, value: Option<i64>) -> Result<(), XlsxError> {
        let column = self.next();
        let format = &self.cursor.styles.integer;
        match value {
            Some(v) => self.cursor.sheet.write_number_with_format(
                self.row,
                column,
                cell::integer(v),
                format,
            )?,
            None => self.cursor.sheet.write_blank(self.row, column, format)?,
        };
        Ok(())
    }

    pub fn date(&mut self, value: Option<NaiveDate>) -> Result<(), XlsxError> {
        let column = self.next();
        let format = &self.cursor.styles.date;
        match value {
            Some(d) => match excel_date(d) {
                Some(excel) => self
                    .cursor
                    .sheet
                    .write_datetime_with_format(self.row, column, excel, format)?,
                // Excel serial dates start at 1900-01-01; write out-of-range dates as ISO text.
                None => self.cursor.sheet.write_string_with_format(
                    self.row,
                    column,
                    d.format("%Y-%m-%d").to_string(),
                    &self.cursor.styles.text,
                )?,
            },
            None => self.cursor.sheet.write_blank(self.row, column, format)?,
        };
        Ok(())
    }

    pub fn date_time(&mut self, value: Option<DateTime<Utc>>) -> Result<(), XlsxError> {
        let column = self.next();
        let format = &self.cursor.styles.date_time;
        match value {
            Some(t) => {
                let local = t.with_timezone(&self.cursor.zone).naive_local();
                match excel_date_time(local) {
                    Some(excel) => self
                        .cursor
                        .sheet
                        .write_datetime_with_format(self.row, column, excel, format)?,
                    None => self.cursor.sheet.write_string_with_format(
                        self.row,
                        column,
                        local.format("%Y-%m-%d %H:%M").to_string(),
                        &self.cursor.styles.text,
                    )?,
                }
            }
            None => self.cursor.sheet.write_blank(self.row, column, format)?,
        };
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use rust_xlsxwriter::Workbook;

    #[test]
    fn a_date_before_1900_is_written_without_error() {
        let styles = Styles::new();
        let mut workbook = Workbook::new();
        let sheet = workbook.add_worksheet();
        let mut cursor = Cursor::new(sheet, &styles, chrono_tz::UTC);
        let pre_1900 = NaiveDate::from_ymd_opt(1895, 6, 15).unwrap();
        cursor.field("Opened", Some(pre_1900)).unwrap();
        let bytes = workbook.save_to_buffer().unwrap();
        assert!(!bytes.is_empty());
    }
}
