package org.involens_be.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.SpreadsheetVersion;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.involens_be.dto.request.VendorItemReportRequest;
import org.involens_be.dto.response.VendorItemReportResponse;
import org.springframework.stereotype.Component;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

@Slf4j
@Component
public class VendorItemReportExcelWriter {
    private static final String[] HEADERS = {"STT", "Mặt hàng", "Số lượng", "Đơn giá",
            "Giá thấp nhất", "Giá cao nhất", "Thành tiền", "Tỷ trọng (%)"};

    /** Creates a numeric Excel report with selection filters, summary totals and all grouped item rows. */
    public byte[] write(VendorItemReportRequest request, VendorItemReportResponse report) throws IOException {
        log.info("Creating supplier item workbook. items={}", report.totalItems());
        try (SXSSFWorkbook workbook = new SXSSFWorkbook(100);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            workbook.setCompressTempFiles(true);
            CellStyle bold = workbook.createCellStyle();
            Font font = workbook.createFont();
            font.setBold(true);
            bold.setFont(font);
            CellStyle number = workbook.createCellStyle();
            number.setDataFormat(workbook.createDataFormat().getFormat("#,##0.00"));
            CellStyle percent = workbook.createCellStyle();
            percent.setDataFormat(workbook.createDataFormat().getFormat("0.0\"%\""));
            Sheet sheet = createSheet(workbook, request, report, bold);
            int rowIndex = 7;
            int ordinal = 0;
            for (var item : report.items()) {
                if (rowIndex >= SpreadsheetVersion.EXCEL2007.getMaxRows() - 1) {
                    sheet.setAutoFilter(new CellRangeAddress(6, rowIndex - 1, 0, 7));
                    sheet = createSheet(workbook, request, report, bold);
                    rowIndex = 7;
                }
                Row row = sheet.createRow(rowIndex++);
                row.createCell(0).setCellValue(++ordinal);
                row.createCell(1).setCellValue(item.itemName());
                writeNumber(row, 2, item.quantity().doubleValue(), number);
                writeNumber(row, 3, item.unitPrice().doubleValue(), number);
                writeNumber(row, 4, item.minUnitPrice().doubleValue(), number);
                writeNumber(row, 5, item.maxUnitPrice().doubleValue(), number);
                writeNumber(row, 6, item.totalAmount().doubleValue(), number);
                writeNumber(row, 7, item.percentage().doubleValue(), percent);
            }
            sheet.setAutoFilter(new CellRangeAddress(6, Math.max(6, rowIndex - 1), 0, 7));
            Row total = sheet.createRow(rowIndex);
            total.createCell(1).setCellValue("Tổng doanh thu");
            total.getCell(1).setCellStyle(bold);
            writeNumber(total, 6, report.totalRevenue().doubleValue(), number);
            workbook.write(output);
            log.info("Supplier item workbook completed. rows={}, sheets={}", ordinal, workbook.getNumberOfSheets());
            return output.toByteArray();
        }
    }

    /** Adds the selected supplier and dates, summary indicators and Vietnamese column headers. */
    private Sheet createSheet(Workbook workbook, VendorItemReportRequest request,
                              VendorItemReportResponse report, CellStyle bold) {
        log.debug("Creating supplier report sheet");
        Sheet sheet = workbook.createSheet("Bao cao " + (workbook.getNumberOfSheets() + 1));
        sheet.createRow(0).createCell(0).setCellValue("Báo cáo mặt hàng theo nhà cung cấp");
        sheet.getRow(0).getCell(0).setCellStyle(bold);
        sheet.createRow(1).createCell(0).setCellValue("Nhà cung cấp (ID): " + request.getVendorId());
        sheet.createRow(2).createCell(0).setCellValue("Từ ngày: " + request.getDateFrom() + " - Đến ngày: " + request.getDateTo());
        Row summary = sheet.createRow(3);
        summary.createCell(0).setCellValue("Tổng doanh thu");
        summary.createCell(1).setCellValue(report.totalRevenue().doubleValue());
        summary.createCell(2).setCellValue("Số hóa đơn");
        summary.createCell(3).setCellValue(report.totalInvoices());
        summary.createCell(4).setCellValue("Số mặt hàng");
        summary.createCell(5).setCellValue(report.totalItems());
        sheet.createRow(4).createCell(0).setCellValue("Chỉ tính hóa đơn thành công. Doanh thu là tổng thành tiền mặt hàng.");
        Row header = sheet.createRow(6);
        for (int column = 0; column < HEADERS.length; column++) {
            header.createCell(column).setCellValue(HEADERS[column]);
            header.getCell(column).setCellStyle(bold);
            sheet.setColumnWidth(column, (column == 1 ? 42 : 20) * 256);
        }
        sheet.createFreezePane(0, 7);
        return sheet;
    }

    /** Writes a numeric report value with its display format, retaining spreadsheet arithmetic support. */
    private void writeNumber(Row row, int column, double value, CellStyle style) {
        log.trace("Writing supplier report numeric cell. row={}, column={}", row.getRowNum(), column);
        Cell cell = row.createCell(column);
        cell.setCellValue(value);
        cell.setCellStyle(style);
    }
}
