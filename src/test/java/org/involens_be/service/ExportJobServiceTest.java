package org.involens_be.service;

import org.involens_be.common.UserRole;
import org.involens_be.dto.request.ExportPreviewRequest;
import org.involens_be.exception.InvalidExportPreviewFilterException;
import org.involens_be.repository.ExportJobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.ss.usermodel.CellType;
import org.involens_be.common.DocumentState;
import org.involens_be.exception.NoInvoicesToExportException;
import org.involens_be.exception.InvoiceExportException;
import org.springframework.data.domain.PageRequest;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ExportJobServiceTest {

    @Mock
    private ExportJobRepository exportJobs;

    private ExportJobService service;

    /** Exports multiple batches with accounting cell types and the employee's approved-only scope. */
    @Test
    void excelExportsAllBatchesWithTypedAccountingValues() throws Exception {
        var request = new ExportPreviewRequest();
        request.setDateFrom("2026-09-01");
        request.setDateTo("2026-09-30");
        request.setStatus(ExportPreviewRequest.Status.APPROVED);
        var row = new ExportJobRepository.InvoiceExportRow(UUID.randomUUID(), "000123",
                LocalDate.of(2026, 9, 15), "Nhà cung cấp", "0012345678", "Hà Nội",
                new BigDecimal("1234567.89"), "VND", DocumentState.EXTRACT_SUCCESS,
                LocalDateTime.of(2026, 9, 16, 10, 30));
        when(exportJobs.findExportBatch(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30),
                true, false, false, 12L, PageRequest.of(0, 1000))).thenReturn(List.of(row));
        when(exportJobs.findExportBatch(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30),
                true, false, false, 12L, PageRequest.of(1, 1000))).thenReturn(List.of(row));
        when(exportJobs.findExportBatch(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30),
                true, false, false, 12L, PageRequest.of(2, 1000))).thenReturn(List.of());

        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(service.exportExcel(request, 12L, UserRole.EMPLOYEE)))) {
            assertEquals(1, workbook.getNumberOfSheets());
            var sheet = workbook.getSheet("Hóa đơn");
            assertEquals(2, sheet.getLastRowNum());
            assertEquals(10, sheet.getRow(0).getLastCellNum());
            assertEquals("Số hóa đơn", sheet.getRow(0).getCell(1).getStringCellValue());
            var first = sheet.getRow(1);
            assertEquals("000123", first.getCell(1).getStringCellValue());
            assertEquals("0012345678", first.getCell(4).getStringCellValue());
            assertEquals(CellType.NUMERIC, first.getCell(2).getCellType());
            assertEquals(row.invoiceDate(), first.getCell(2).getLocalDateTimeCellValue().toLocalDate());
            assertEquals(1234567.89, first.getCell(6).getNumericCellValue(), 0.001);
            assertEquals("Đã duyệt", first.getCell(8).getStringCellValue());
            assertEquals(row.approvedAt(), first.getCell(9).getLocalDateTimeCellValue());
            assertEquals(2, sheet.getRow(2).getCell(0).getNumericCellValue());
        }
    }

    /** Allows managers to export pending invoices across uploaders without inventing approval dates. */
    @Test
    void managerPendingExportKeepsMissingValuesBlankAndTextLiteral() throws Exception {
        var request = new ExportPreviewRequest();
        request.setStatus(ExportPreviewRequest.Status.PENDING_REVIEW);
        var row = new ExportJobRepository.InvoiceExportRow(UUID.randomUUID(), "=1+1",
                LocalDate.of(2026, 9, 15), "Seller", null, null, BigDecimal.TEN,
                "USD", DocumentState.REVIEW_REQUIRED, null);
        when(exportJobs.findExportBatch(null, null, false, true, true, 9L, PageRequest.of(0, 1000)))
                .thenReturn(List.of(row));
        when(exportJobs.findExportBatch(null, null, false, true, true, 9L, PageRequest.of(1, 1000)))
                .thenReturn(List.of());
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(service.exportExcel(request, 9L, UserRole.MANAGER)))) {
            var cells = workbook.getSheetAt(0).getRow(1);
            assertEquals(CellType.STRING, cells.getCell(1).getCellType());
            assertEquals("=1+1", cells.getCell(1).getStringCellValue());
            assertEquals(CellType.BLANK, cells.getCell(4).getCellType());
            assertEquals("Chờ duyệt", cells.getCell(8).getStringCellValue());
            assertEquals(CellType.BLANK, cells.getCell(9).getCellType());
        }
    }

    /** Returns the empty-selection business error instead of producing a header-only workbook. */
    @Test
    void emptyExcelSelectionIsRejected() {
        when(exportJobs.findExportBatch(null, null, false, false, false, 12L, PageRequest.of(0, 1000)))
                .thenReturn(List.of());
        assertThrows(NoInvoicesToExportException.class,
                () -> service.exportExcel(new ExportPreviewRequest(), 12L, UserRole.EMPLOYEE));
    }

    /** Rejects invalid export dates before any invoice data is loaded. */
    @Test
    void excelRejectsMalformedAndReversedDates() {
        var request = new ExportPreviewRequest();
        request.setDateFrom("bad-date");
        assertThrows(InvalidExportPreviewFilterException.class,
                () -> service.exportExcel(request, 12L, UserRole.EMPLOYEE));
        request.setDateFrom("2026-09-30");
        request.setDateTo("2026-09-01");
        assertThrows(InvalidExportPreviewFilterException.class,
                () -> service.exportExcel(request, 12L, UserRole.EMPLOYEE));
        verifyNoInteractions(exportJobs);
    }

    /** Converts workbook I/O failures into the approved export failure while preserving their cause. */
    @Test
    void excelCreationFailureIsWrapped() throws Exception {
        var writer = mock(InvoiceExcelWriter.class);
        var failure = new IOException("disk failure");
        when(writer.write(any())).thenThrow(failure);
        service = new ExportJobService(exportJobs, writer);
        var exception = assertThrows(InvoiceExportException.class,
                () -> service.exportExcel(new ExportPreviewRequest(), 12L, UserRole.EMPLOYEE));
        assertSame(failure, exception.getCause());
    }

    /** Creates an isolated export-preview service for each business-rule test. */
    @BeforeEach
    void setUp() {
        service = new ExportJobService(exportJobs, new InvoiceExcelWriter());
    }

    /** Returns document-state aggregates for a manager requesting all exportable invoices. */
    @Test
    void allStatusReturnsAllCountsForManager() {
        var request = new ExportPreviewRequest();
        request.setDateFrom("2026-09-01");
        request.setDateTo("2026-09-30");
        when(exportJobs.countPreview(LocalDate.parse(request.getDateFrom()), LocalDate.parse(request.getDateTo()),
                false, false, true, 9L))
                .thenReturn(new ExportJobRepository.PreviewCounts(245L, 198L, 47L));

        var result = service.preview(request, 9L, UserRole.MANAGER);

        assertEquals(245L, result.getTotalInvoices());
        assertEquals(198L, result.getApproved());
        assertEquals(47L, result.getPendingReview());
    }

    /** Restricts an employee's extracted-success preview and hides the pending-review group. */
    @Test
    void approvedStatusUsesEmployeeScopeAndNullPendingCount() {
        var request = new ExportPreviewRequest();
        request.setStatus(ExportPreviewRequest.Status.APPROVED);
        when(exportJobs.countPreview(null, null, true, false, false, 12L))
                .thenReturn(new ExportJobRepository.PreviewCounts(4L, 4L, 0L));

        var result = service.preview(request, 12L, UserRole.EMPLOYEE);

        assertEquals(4L, result.getTotalInvoices());
        assertEquals(4L, result.getApproved());
        assertNull(result.getPendingReview());
    }

    /** Rejects a reversed invoice-date range without querying invoice aggregates. */
    @Test
    void reversedDateRangeIsRejected() {
        var request = new ExportPreviewRequest();
        request.setDateFrom("2026-09-30");
        request.setDateTo("2026-09-01");

        assertThrows(InvalidExportPreviewFilterException.class,
                () -> service.preview(request, 12L, UserRole.EMPLOYEE));
        verifyNoInteractions(exportJobs);
    }

    /** Treats literal null date parameters from the export screen as absent filters. */
    @Test
    void literalNullDatesAreAccepted() {
        var request = new ExportPreviewRequest();
        request.setDateFrom("null");
        request.setDateTo("null");
        when(exportJobs.countPreview(null, null, false, false, false, 12L))
                .thenReturn(new ExportJobRepository.PreviewCounts(0L, 0L, 0L));

        var result = service.preview(request, 12L, UserRole.EMPLOYEE);

        assertEquals(0L, result.getTotalInvoices());
        verify(exportJobs).countPreview(null, null, false, false, false, 12L);
    }
}
