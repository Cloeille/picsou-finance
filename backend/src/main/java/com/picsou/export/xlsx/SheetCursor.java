package com.picsou.export.xlsx;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * A write head over one sheet: appends rows top to bottom, so a writer never has to track row
 * indices by hand across optional blocks.
 *
 * <p>Every numeric and temporal value goes in as a typed cell rather than a formatted string —
 * this export exists so the figures can be summed and sorted outside Picsou, which a text cell
 * silently prevents.
 */
final class SheetCursor {

    private final Sheet sheet;
    private final WorkbookStyles styles;
    private int rowIndex = 0;

    SheetCursor(Sheet sheet, WorkbookStyles styles) {
        this.sheet = sheet;
        this.styles = styles;
    }

    /** A bold standalone line introducing a block. */
    void title(String value) {
        Row row = sheet.createRow(rowIndex++);
        cell(row, 0, value, styles.title);
    }

    /** One blank row, used to separate blocks. Skipped at the top of a sheet. */
    void blank() {
        if (rowIndex > 0) rowIndex++;
    }

    /** A bold header row for a table. */
    void headerRow(List<String> headers) {
        Row row = sheet.createRow(rowIndex++);
        for (int i = 0; i < headers.size(); i++) {
            cell(row, i, headers.get(i), styles.header);
        }
    }

    /** Starts a row and returns it for value-by-value writing. */
    RowCursor row() {
        return new RowCursor(sheet.createRow(rowIndex++));
    }

    /** A two-column {@code label: value} line, for the header and detail blocks. */
    void field(String label, Object value) {
        RowCursor row = row();
        row.text(label, styles.fieldName);
        row.auto(value);
    }

    /** A {@code label: value} line whose value is a figure already expressed out of 100. */
    void fieldPercent(String label, BigDecimal value) {
        RowCursor row = row();
        row.text(label, styles.fieldName);
        row.percent(value);
    }

    private void cell(Row row, int column, String value, CellStyle style) {
        Cell cell = row.createCell(column);
        cell.setCellValue(value);
        cell.setCellStyle(style);
    }

    /** A write head over one row, advancing a column at a time. */
    final class RowCursor {

        private final Row row;
        private int column = 0;

        private RowCursor(Row row) {
            this.row = row;
        }

        /** Writes whatever the value's runtime type calls for, leaving a null cell empty. */
        void auto(Object value) {
            if (value == null) {
                column++;
                return;
            }
            if (value instanceof BigDecimal d) {
                number(d, styles.money);
            } else if (value instanceof Integer i) {
                number(BigDecimal.valueOf(i), styles.integer);
            } else if (value instanceof Short s) {
                number(BigDecimal.valueOf(s), styles.integer);
            } else if (value instanceof LocalDate d) {
                date(d);
            } else if (value instanceof Instant i) {
                dateTime(i);
            } else if (value instanceof Enum<?> e) {
                text(e.name(), styles.text);
            } else {
                text(String.valueOf(value), styles.text);
            }
        }

        void text(String value, CellStyle style) {
            Cell cell = row.createCell(column++);
            if (value != null) cell.setCellValue(value);
            cell.setCellStyle(style);
        }

        void text(String value) {
            text(value, styles.text);
        }

        void number(BigDecimal value, CellStyle style) {
            Cell cell = row.createCell(column++);
            if (value != null) cell.setCellValue(value.doubleValue());
            cell.setCellStyle(style);
        }

        void money(BigDecimal value) {
            number(value, styles.money);
        }

        void quantity(BigDecimal value) {
            number(value, styles.quantity);
        }

        void percent(BigDecimal value) {
            number(value, styles.percent);
        }

        void integer(Number value) {
            number(value == null ? null : BigDecimal.valueOf(value.longValue()), styles.integer);
        }

        void date(LocalDate value) {
            Cell cell = row.createCell(column++);
            if (value != null) cell.setCellValue(value);
            cell.setCellStyle(styles.date);
        }

        void dateTime(Instant value) {
            Cell cell = row.createCell(column++);
            if (value != null) {
                cell.setCellValue(LocalDateTime.ofInstant(value, ZoneId.systemDefault()));
            }
            cell.setCellStyle(styles.dateTime);
        }
    }
}
