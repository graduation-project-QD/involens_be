package org.involens_be.service;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.involens_be.dto.request.VendorItemReportRequest;
import org.involens_be.dto.response.VendorItemReportResponse;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

@Slf4j
class VendorItemReportExcelWriterTest {
    /** Reopens the generated workbook to verify numeric amounts, percent formatting and empty export support. */
    @Test
    void writesItemsAndEmptyWorkbook() throws Exception {
        log.info("Testing supplier report workbook contents");
        var request = new VendorItemReportRequest();
        request.setVendorId(3L);
        request.setDateFrom("2026-01-01");
        request.setDateTo("2026-01-31");
        var item = new VendorItemReportResponse.Item("=Rice", BigDecimal.TEN, BigDecimal.TEN,
                BigDecimal.TEN, BigDecimal.TEN, new BigDecimal("100"), new BigDecimal("100.0"));
        var writer = new VendorItemReportExcelWriter();
        for (var items : List.of(List.of(item), List.<VendorItemReportResponse.Item>of())) {
            var revenue = items.isEmpty() ? BigDecimal.ZERO : new BigDecimal("100");
            var report = new VendorItemReportResponse(revenue, items.size(), items.size(), items);
            try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(writer.write(request, report)))) {
                var sheet = workbook.getSheetAt(0);
                assertEquals("Mặt hàng", sheet.getRow(6).getCell(1).getStringCellValue());
                assertEquals(revenue.doubleValue(), sheet.getRow(3).getCell(1).getNumericCellValue());
                assertEquals(revenue.doubleValue(), sheet.getRow(7 + items.size()).getCell(6).getNumericCellValue());
                if (!items.isEmpty()) {
                    assertEquals("=Rice", sheet.getRow(7).getCell(1).getStringCellValue());
                    assertEquals(100d, sheet.getRow(7).getCell(7).getNumericCellValue());
                    assertEquals("0.0\"%\"", sheet.getRow(7).getCell(7).getCellStyle().getDataFormatString());
                }
            }
        }
    }
}
