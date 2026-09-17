package com.picsou.export.xlsx;

import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.Workbook;

/**
 * The handful of cell styles the export uses, created once per workbook.
 *
 * <p>POI caps a workbook at 64k styles and creating one per cell blows through that on a large
 * export, so these are built up front and shared.
 */
final class WorkbookStyles {

    final CellStyle title;
    final CellStyle header;
    final CellStyle fieldName;
    final CellStyle text;
    final CellStyle money;
    final CellStyle quantity;
    /** For a figure already expressed out of 100 — never Excel's {@code 0.00%}, which rescales. */
    final CellStyle percent;
    final CellStyle integer;
    final CellStyle date;
    final CellStyle dateTime;

    WorkbookStyles(Workbook wb) {
        Font boldFont = wb.createFont();
        boldFont.setBold(true);

        Font titleFont = wb.createFont();
        titleFont.setBold(true);
        titleFont.setFontHeightInPoints((short) 12);

        this.title = wb.createCellStyle();
        this.title.setFont(titleFont);

        this.header = wb.createCellStyle();
        this.header.setFont(boldFont);
        this.header.setAlignment(HorizontalAlignment.LEFT);

        this.fieldName = wb.createCellStyle();
        this.fieldName.setFont(boldFont);

        this.text = wb.createCellStyle();

        this.money = numeric(wb, "#,##0.00");
        this.quantity = numeric(wb, "#,##0.########");
        this.percent = numeric(wb, "#,##0.00\" %\"");
        this.integer = numeric(wb, "0");
        this.date = numeric(wb, "dd/mm/yyyy");
        this.dateTime = numeric(wb, "dd/mm/yyyy hh:mm");
    }

    private static CellStyle numeric(Workbook wb, String format) {
        CellStyle style = wb.createCellStyle();
        style.setDataFormat(wb.createDataFormat().getFormat(format));
        return style;
    }
}
