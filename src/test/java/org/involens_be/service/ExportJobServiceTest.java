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
import org.springframework.data.domain.Pageable;

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
        request.setVendorIds(List.of(1L, 2L));
        request.setDateFrom("2026-09-01");
        request.setDateTo("2026-09-30");
        request.setStatus(ExportPreviewRequest.Status.APPROVED);
        var row = new ExportJobRepository.InvoiceExportRow(UUID.randomUUID(), "000123",
                LocalDate.of(2026, 9, 15), "Nhà cung cấp", "0012345678", "Hà Nội",
                new BigDecimal("1234567.89"), "VND", DocumentState.EXTRACT_SUCCESS,
                LocalDateTime.of(2026, 9, 16, 10, 30));
        when(exportJobs.findExportBatch(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30),
                true, false, false, true, List.of(1L, 2L), 12L, PageRequest.of(0, 1000))).thenReturn(List.of(row));
        when(exportJobs.findExportBatch(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30),
                true, false, false, true, List.of(1L, 2L), 12L, PageRequest.of(1, 1000))).thenReturn(List.of(row));
        when(exportJobs.findExportBatch(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30),
                true, false, false, true, List.of(1L, 2L), 12L, PageRequest.of(2, 1000))).thenReturn(List.of());

        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(service.exportExcel(request, 12L, UserRole.EMPLOYEE)))) {
            assertEquals(1, workbook.getNumberOfSheets());
            var sheet = workbook.getSheet("Hóa đơn");
            assertEquals(2, sheet.getLastRowNum());
            assertEquals(6, sheet.getRow(0).getLastCellNum());
            String[] expectedHeaders = {"STT", "Ngày hóa đơn", "Nhà cung cấp",
                    "Địa chỉ nhà cung cấp", "Tổng tiền", "Trạng thái"};
            for (int column = 0; column < expectedHeaders.length; column++) {
                assertEquals(expectedHeaders[column], sheet.getRow(0).getCell(column).getStringCellValue());
            }
            var first = sheet.getRow(1);
            assertEquals("Nhà cung cấp", first.getCell(2).getStringCellValue());
            assertEquals("Hà Nội", first.getCell(3).getStringCellValue());
            assertEquals(CellType.NUMERIC, first.getCell(1).getCellType());
            assertEquals(row.invoiceDate(), first.getCell(1).getLocalDateTimeCellValue().toLocalDate());
            assertEquals(1234567.89, first.getCell(4).getNumericCellValue(), 0.001);
            assertEquals("Đã duyệt", first.getCell(5).getStringCellValue());
            assertNull(first.getCell(6));
            assertEquals(2, sheet.getRow(2).getCell(0).getNumericCellValue());
        }
    }

    /** Allows managers to export pending invoices across uploaders without inventing approval dates. */
    @Test
    void managerPendingExportKeepsMissingValuesBlankAndTextLiteral() throws Exception {
        var request = new ExportPreviewRequest();
        request.setStatus(ExportPreviewRequest.Status.PENDING_REVIEW);
        var row = new ExportJobRepository.InvoiceExportRow(UUID.randomUUID(), "=1+1",
                LocalDate.of(2026, 9, 15), "=1+1", null, null, BigDecimal.TEN,
                "USD", DocumentState.REVIEW_REQUIRED, null);
        when(exportJobs.findExportBatch(null, null, false, true, true, false, java.util.Collections.singletonList(null), 9L, PageRequest.of(0, 1000)))
                .thenReturn(List.of(row));
        when(exportJobs.findExportBatch(null, null, false, true, true, false, java.util.Collections.singletonList(null), 9L, PageRequest.of(1, 1000)))
                .thenReturn(List.of());
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(service.exportExcel(request, 9L, UserRole.MANAGER)))) {
            var cells = workbook.getSheetAt(0).getRow(1);
            assertEquals(CellType.STRING, cells.getCell(2).getCellType());
            assertEquals("=1+1", cells.getCell(2).getStringCellValue());
            assertEquals(CellType.BLANK, cells.getCell(3).getCellType());
            assertEquals("Chờ duyệt", cells.getCell(5).getStringCellValue());
            assertNull(cells.getCell(6));
        }
    }

    /** Returns the empty-selection business error instead of producing a header-only workbook. */
    @Test
    void emptyExcelSelectionIsRejected() {
        when(exportJobs.findExportBatch(null, null, false, false, false, false, java.util.Collections.singletonList(null), 12L, PageRequest.of(0, 1000)))
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
        service = new ExportJobService(exportJobs, writer, new InvoiceCsvWriter());
        var exception = assertThrows(InvoiceExportException.class,
                () -> service.exportExcel(new ExportPreviewRequest(), 12L, UserRole.EMPLOYEE));
        assertSame(failure, exception.getCause());
    }

    /** Creates an isolated export-preview service for each business-rule test. */
    @BeforeEach
    void setUp() {
        service = new ExportJobService(exportJobs, new InvoiceExcelWriter(), new InvoiceCsvWriter());
    }

    /** Preserves Vietnamese supplier text and CSV delimiters across all employee-selected batches. */
    @Test
    void csvExportsAllBatchesWithEscapedValues() {
        var request = new ExportPreviewRequest();
        request.setVendorIds(List.of(1L, 2L));
        request.setDateFrom("2026-09-01");
        request.setDateTo("2026-09-30");
        request.setStatus(ExportPreviewRequest.Status.APPROVED);
        var row = new ExportJobRepository.InvoiceExportRow(UUID.randomUUID(), "omit-number",
                LocalDate.of(2026, 9, 15), "Công ty \"A\", B", "omit-tax", "Hà Nội\r\nViệt Nam",
                new BigDecimal("1234567.89"), "VND", DocumentState.EXTRACT_SUCCESS, LocalDateTime.now());
        when(exportJobs.findExportBatch(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30),
                true, false, false, true, List.of(1L, 2L), 12L, PageRequest.of(0, 1000))).thenReturn(List.of(row));
        when(exportJobs.findExportBatch(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30),
                true, false, false, true, List.of(1L, 2L), 12L, PageRequest.of(1, 1000))).thenReturn(List.of(row));
        when(exportJobs.findExportBatch(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30),
                true, false, false, true, List.of(1L, 2L), 12L, PageRequest.of(2, 1000))).thenReturn(List.of());
        String csv = new String(service.exportCsv(request, 12L, UserRole.EMPLOYEE),
                java.nio.charset.StandardCharsets.UTF_8);
        String values = ",\"15/09/2026\",\"Công ty \"\"A\"\", B\",\"Hà Nội\r\nViệt Nam\",\"1234567.89\",\"Đã duyệt\"\r\n";
        assertEquals("\uFEFFSTT,Ngày hóa đơn,Nhà cung cấp,Địa chỉ nhà cung cấp,Tổng tiền,Trạng thái\r\n"
                + "\"1\"" + values + "\"2\"" + values, csv);
    }

    /** Exports manager-visible pending invoices without inventing values for missing fields. */
    @Test
    void csvManagerPendingExportPreservesBlanks() {
        var request = new ExportPreviewRequest();
        request.setStatus(ExportPreviewRequest.Status.PENDING_REVIEW);
        var row = new ExportJobRepository.InvoiceExportRow(UUID.randomUUID(), null, null, null,
                null, null, null, "VND", DocumentState.REVIEW_REQUIRED, null);
        when(exportJobs.findExportBatch(null, null, false, true, true, false, java.util.Collections.singletonList(null), 9L, PageRequest.of(0, 1000)))
                .thenReturn(List.of(row));
        when(exportJobs.findExportBatch(null, null, false, true, true, false, java.util.Collections.singletonList(null), 9L, PageRequest.of(1, 1000)))
                .thenReturn(List.of());
        String csv = new String(service.exportCsv(request, 9L, UserRole.MANAGER),
                java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(csv.endsWith("\"1\",\"\",,,\"\",\"Chờ duyệt\"\r\n"));
    }

    /** Rejects empty CSV selections using the shared invoice-selection error. */
    @Test
    void csvEmptySelectionIsRejected() {
        when(exportJobs.findExportBatch(null, null, false, false, false, false, java.util.Collections.singletonList(null), 12L, PageRequest.of(0, 1000)))
                .thenReturn(List.of());
        assertThrows(NoInvoicesToExportException.class,
                () -> service.exportCsv(new ExportPreviewRequest(), 12L, UserRole.EMPLOYEE));
    }

    /** Rejects malformed or reversed CSV dates before accessing invoice data. */
    @Test
    void csvRejectsInvalidDates() {
        var request = new ExportPreviewRequest();
        request.setDateFrom("bad-date");
        assertThrows(InvalidExportPreviewFilterException.class,
                () -> service.exportCsv(request, 12L, UserRole.EMPLOYEE));
        request.setDateFrom("2026-09-30");
        request.setDateTo("2026-09-01");
        assertThrows(InvalidExportPreviewFilterException.class,
                () -> service.exportCsv(request, 12L, UserRole.EMPLOYEE));
        verifyNoInteractions(exportJobs);
    }

    /** Preserves CSV generation failures and identifies the failed download format. */
    @Test
    void csvCreationFailureIsWrapped() throws Exception {
        var writer = mock(InvoiceCsvWriter.class);
        var failure = new IOException("write failure");
        when(writer.write(any())).thenThrow(failure);
        service = new ExportJobService(exportJobs, new InvoiceExcelWriter(), writer);
        var exception = assertThrows(InvoiceExportException.class,
                () -> service.exportCsv(new ExportPreviewRequest(), 12L, UserRole.EMPLOYEE));
        assertSame(failure, exception.getCause());
        assertEquals("Xuất file CSV thất bại. Vui lòng thử lại.", exception.getMessage());
    }

    /** Returns document-state aggregates for a manager requesting all exportable invoices. */
    @Test
    void allStatusReturnsAllCountsForManager() {
        var request = new ExportPreviewRequest();
        request.setDateFrom("2026-09-01");
        request.setDateTo("2026-09-30");
        when(exportJobs.countPreview(LocalDate.parse(request.getDateFrom()), LocalDate.parse(request.getDateTo()),
                false, false, true, false, java.util.Collections.singletonList(null), 9L))
                .thenReturn(new ExportJobRepository.PreviewCounts(245L, 198L, 47L));

        var result = service.preview(request, 9L, UserRole.MANAGER);

        assertEquals(245L, result.getTotalInvoices());
        assertEquals(198L, result.getApproved());
        assertEquals(47L, result.getPendingReview());
    }

    /** Returns more than five preview rows with the same values and order as the Excel download. */
    @Test
    void previewReturnsAllSelectedInvoicesInExcelOrder() throws Exception {
        var request = new ExportPreviewRequest();
        request.setVendorIds(List.of(1L, 2L));
        request.setDateFrom("2026-09-01");
        request.setDateTo("2026-09-30");
        var rows = new java.util.ArrayList<ExportJobRepository.InvoiceExportRow>();
        for (int index = 1; index <= 6; index++) {
            rows.add(new ExportJobRepository.InvoiceExportRow(UUID.randomUUID(), "000" + index,
                    LocalDate.of(2026, 9, index), "Supplier " + index, null, null,
                    new BigDecimal("125000.50"), "VND",
                    index % 2 == 0 ? DocumentState.REVIEW_REQUIRED : DocumentState.EXTRACT_SUCCESS, null));
        }
        var from = LocalDate.of(2026, 9, 1);
        var to = LocalDate.of(2026, 9, 30);
        when(exportJobs.countPreview(from, to, false, false, true, true, List.of(1L, 2L), 9L))
                .thenReturn(new ExportJobRepository.PreviewCounts(6L, 3L, 3L));
        when(exportJobs.findExportBatch(from, to, false, false, true, true, List.of(1L, 2L), 9L, Pageable.unpaged()))
                .thenReturn(rows);
        when(exportJobs.findExportBatch(from, to, false, false, true, true, List.of(1L, 2L), 9L, PageRequest.of(0, 1000)))
                .thenReturn(rows);
        when(exportJobs.findExportBatch(from, to, false, false, true, true, List.of(1L, 2L), 9L, PageRequest.of(1, 1000)))
                .thenReturn(List.of());

        var preview = service.preview(request, 9L, UserRole.MANAGER);

        assertEquals(6, preview.getItems().size());
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(service.exportExcel(request, 9L, UserRole.MANAGER)))) {
            var sheet = workbook.getSheetAt(0);
            assertEquals(sheet.getLastRowNum(), preview.getItems().size());
            for (int index = 0; index < preview.getItems().size(); index++) {
                var item = preview.getItems().get(index);
                var cells = sheet.getRow(index + 1);
                assertEquals(rows.get(index).invoiceNumber(), item.getInvoiceNumber());
                assertEquals(cells.getCell(2).getStringCellValue(), item.getSellerName());
                assertEquals(cells.getCell(1).getLocalDateTimeCellValue().toLocalDate(), item.getInvoiceDate());
                assertEquals(cells.getCell(4).getNumericCellValue(), item.getTotalAmount().doubleValue());
                assertEquals(rows.get(index).state() == DocumentState.EXTRACT_SUCCESS
                        ? ExportPreviewRequest.Status.APPROVED : ExportPreviewRequest.Status.PENDING_REVIEW,
                        item.getStatus());
            }
        }
    }

    /** Restricts an employee's extracted-success preview and hides the pending-review group. */
    @Test
    void approvedStatusUsesEmployeeScopeAndNullPendingCount() {
        var request = new ExportPreviewRequest();
        request.setStatus(ExportPreviewRequest.Status.APPROVED);
        when(exportJobs.countPreview(null, null, true, false, false, false, java.util.Collections.singletonList(null), 12L))
                .thenReturn(new ExportJobRepository.PreviewCounts(4L, 4L, 0L));

        var result = service.preview(request, 12L, UserRole.EMPLOYEE);

        assertEquals(4L, result.getTotalInvoices());
        assertEquals(4L, result.getApproved());
        assertNull(result.getPendingReview());
        verify(exportJobs).findExportBatch(null, null, true, false, false, false, java.util.Collections.singletonList(null), 12L, Pageable.unpaged());
    }

    /** Keeps missing invoice values and limits pending previews to the employee's export scope. */
    @Test
    void pendingPreviewPreservesMissingInvoiceValues() {
        var request = new ExportPreviewRequest();
        request.setStatus(ExportPreviewRequest.Status.PENDING_REVIEW);
        when(exportJobs.countPreview(null, null, false, true, false, false, java.util.Collections.singletonList(null), 12L))
                .thenReturn(new ExportJobRepository.PreviewCounts(1L, 0L, 1L));
        when(exportJobs.findExportBatch(null, null, false, true, false, false, java.util.Collections.singletonList(null), 12L, Pageable.unpaged()))
                .thenReturn(List.of(new ExportJobRepository.InvoiceExportRow(UUID.randomUUID(), null,
                        null, null, null, null, null, "VND", DocumentState.REVIEW_REQUIRED, null)));

        var result = service.preview(request, 12L, UserRole.EMPLOYEE);

        assertNull(result.getApproved());
        assertEquals(1L, result.getPendingReview());
        var item = result.getItems().getFirst();
        assertNull(item.getInvoiceNumber());
        assertNull(item.getSellerName());
        assertNull(item.getInvoiceDate());
        assertNull(item.getTotalAmount());
        assertEquals(ExportPreviewRequest.Status.PENDING_REVIEW, item.getStatus());
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
        request.setVendorIds(List.of());
        request.setDateFrom("null");
        request.setDateTo("null");
        when(exportJobs.countPreview(null, null, false, false, false, false, java.util.Collections.singletonList(null), 12L))
                .thenReturn(new ExportJobRepository.PreviewCounts(0L, 0L, 0L));

        var result = service.preview(request, 12L, UserRole.EMPLOYEE);

        assertEquals(0L, result.getTotalInvoices());
        assertTrue(result.getItems().isEmpty());
        verify(exportJobs).countPreview(null, null, false, false, false, false, java.util.Collections.singletonList(null), 12L);
    }
}
