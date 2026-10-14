package org.involens_be.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.SpreadsheetVersion;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.involens_be.common.DocumentState;
import org.involens_be.exception.NoInvoicesToExportException;
import org.involens_be.repository.ExportJobRepository.InvoiceExportRow;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.function.IntFunction;

@Slf4j
@Component
public class InvoiceExcelWriter {

    private static final String[] HEADERS = {
            "STT", "Số hóa đơn", "Ngày hóa đơn", "Nhà cung cấp", "Mã số thuế",
            "Địa chỉ nhà cung cấp", "Tổng tiền", "Loại tiền", "Trạng thái", "Ngày duyệt"
    };

    /** Creates the invoice-only workbook from bounded database batches, splitting full Excel sheets. */
    public byte[] write(IntFunction<List<InvoiceExportRow>> loadBatch) throws IOException {
        log.info("Starting invoice accounting workbook creation");
        try (SXSSFWorkbook workbook = new SXSSFWorkbook(100);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            workbook.setCompressTempFiles(true);
            CellStyle headerStyle = workbook.createCellStyle();
            Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);
            CellStyle dateStyle = workbook.createCellStyle();
            dateStyle.setDataFormat(workbook.createDataFormat().getFormat("dd/mm/yyyy"));
            CellStyle timestampStyle = workbook.createCellStyle();
            timestampStyle.setDataFormat(workbook.createDataFormat().getFormat("dd/mm/yyyy hh:mm:ss"));
            CellStyle amountStyle = workbook.createCellStyle();
            amountStyle.setDataFormat(workbook.createDataFormat().getFormat("#,##0.00"));
            Sheet sheet = null;
            long count = 0;
            int rowIndex = 1;
            int batchIndex = 0;
            List<InvoiceExportRow> batch;
            while (!(batch = loadBatch.apply(batchIndex++)).isEmpty()) {
                for (InvoiceExportRow invoice : batch) {
                    if (sheet == null || rowIndex == SpreadsheetVersion.EXCEL2007.getMaxRows()) {
                        if (sheet != null) {
                            sheet.setAutoFilter(new CellRangeAddress(0, rowIndex - 1, 0, HEADERS.length - 1));
                        }
                        sheet = createSheet(workbook, headerStyle);
                        rowIndex = 1;
                    }
                    writeInvoice(sheet.createRow(rowIndex++), invoice, ++count,
                            dateStyle, timestampStyle, amountStyle);
                }
                log.debug("Invoice export batch written. batch={}, totalRows={}", batchIndex, count);
            }
            if (count == 0) {
                throw new NoInvoicesToExportException();
            }
            sheet.setAutoFilter(new CellRangeAddress(0, rowIndex - 1, 0, HEADERS.length - 1));
            workbook.write(output);
            log.info("Invoice workbook created successfully. rows={}, sheets={}", count, workbook.getNumberOfSheets());
            return output.toByteArray();
        }
    }

    /** Adds the approved Vietnamese accounting headers and freezes them on each invoice sheet. */
    private Sheet createSheet(Workbook workbook, CellStyle headerStyle) {
        int sheetNumber = workbook.getNumberOfSheets() + 1;
        Sheet sheet = workbook.createSheet(sheetNumber == 1 ? "Hóa đơn" : "Hóa đơn " + sheetNumber);
        Row header = sheet.createRow(0);
        for (int column = 0; column < HEADERS.length; column++) {
            Cell cell = header.createCell(column);
            cell.setCellValue(HEADERS[column]);
            cell.setCellStyle(headerStyle);
            sheet.setColumnWidth(column, (column == 3 || column == 5 ? 40 : 22) * 256);
        }
        sheet.createFreezePane(0, 1);
        log.debug("Created invoice worksheet. name={}", sheet.getSheetName());
        return sheet;
    }

    /** Writes accounting values, keeping identifiers as text and amounts and dates as Excel values. */
    private void writeInvoice(Row row, InvoiceExportRow source, long ordinal,
                              CellStyle dateStyle, CellStyle timestampStyle, CellStyle amountStyle) {
        var invoice = source;
        row.createCell(0).setCellValue(ordinal);
        row.createCell(1).setCellValue(invoice.invoiceNumber());
        Cell date = row.createCell(2);
        if (invoice.invoiceDate() != null) {
            date.setCellValue(invoice.invoiceDate());
            date.setCellStyle(dateStyle);
        }
        row.createCell(3).setCellValue(invoice.sellerName());
        row.createCell(4).setCellValue(invoice.taxCode());
        row.createCell(5).setCellValue(invoice.sellerAddress());
        Cell amount = row.createCell(6);
        if (invoice.totalAmount() != null) {
            amount.setCellValue(invoice.totalAmount().doubleValue());
            amount.setCellStyle(amountStyle);
        }
        row.createCell(7).setCellValue(invoice.currency());
        row.createCell(8).setCellValue(source.state() == DocumentState.EXTRACT_SUCCESS ? "Đã duyệt" : "Chờ duyệt");
        Cell approvedAt = row.createCell(9);
        if (invoice.approvedAt() != null) {
            approvedAt.setCellValue(invoice.approvedAt());
            approvedAt.setCellStyle(timestampStyle);
        }
        log.trace("Invoice written to accounting workbook. invoiceId={}, ordinal={}", invoice.id(), ordinal);
    }
}
