package org.involens_be.controller;

import org.involens_be.common.UserRole;
import org.involens_be.dto.request.ExportPreviewRequest;
import org.involens_be.dto.response.ExportPreviewResponse;
import org.involens_be.exception.GlobalExceptionHandler;
import org.involens_be.service.ExportJobService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ExportJobControllerTest {

    private ExportJobService service;
    private MockMvc mvc;

    /** Binds multiple selected suppliers consistently for both downloads and their preview. */
    @Test
    void supplierIdsBindForAllExportEndpoints() throws Exception {
        when(service.exportExcel(any(), eq(12L), eq(UserRole.EMPLOYEE))).thenReturn(new byte[0]);
        when(service.exportCsv(any(), eq(12L), eq(UserRole.EMPLOYEE))).thenReturn(new byte[0]);
        when(service.preview(any(), eq(12L), eq(UserRole.EMPLOYEE)))
                .thenReturn(ExportPreviewResponse.builder().totalInvoices(0L).items(List.of()).build());
        for (String endpoint : List.of("excel", "csv", "preview")) {
            mvc.perform(post("/api/v1/export-jobs/" + endpoint).param("vendorIds", "1", "2"))
                    .andExpect(status().isOk());
        }
        var requests = org.mockito.ArgumentCaptor.forClass(ExportPreviewRequest.class);
        verify(service).exportExcel(requests.capture(), eq(12L), eq(UserRole.EMPLOYEE));
        verify(service).exportCsv(requests.capture(), eq(12L), eq(UserRole.EMPLOYEE));
        verify(service).preview(requests.capture(), eq(12L), eq(UserRole.EMPLOYEE));
        for (ExportPreviewRequest request : requests.getAllValues()) {
            org.junit.jupiter.api.Assertions.assertEquals(List.of(1L, 2L), request.getVendorIds());
        }
    }

    /** Rejects nonnumeric supplier selections before any export business logic runs. */
    @Test
    void invalidSupplierIdsReturnBadRequest() throws Exception {
        for (String endpoint : List.of("excel", "csv", "preview")) {
            mvc.perform(post("/api/v1/export-jobs/" + endpoint).param("vendorIds", "invalid"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        }
        verifyNoInteractions(service);
    }

    /** Returns CSV attachment bytes and binds the selected filters with the authenticated employee. */
    @Test
    void csvReturnsDownloadAndBindsFilters() throws Exception {
        byte[] bytes = "\uFEFFSTT,Ngày hóa đơn\r\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        when(service.exportCsv(any(), eq(12L), eq(UserRole.EMPLOYEE))).thenAnswer(invocation -> {
            ExportPreviewRequest request = invocation.getArgument(0);
            org.junit.jupiter.api.Assertions.assertEquals("2026-09-01", request.getDateFrom());
            org.junit.jupiter.api.Assertions.assertEquals("2026-09-30", request.getDateTo());
            org.junit.jupiter.api.Assertions.assertEquals(ExportPreviewRequest.Status.APPROVED, request.getStatus());
            return bytes;
        });
        mvc.perform(post("/api/v1/export-jobs/csv").param("dateFrom", "2026-09-01")
                        .param("dateTo", "2026-09-30").param("status", "APPROVED"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("text/csv;charset=UTF-8"))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"invoices.csv\""))
                .andExpect(content().bytes(bytes));
    }

    /** Reports an empty CSV selection through the existing JSON error envelope. */
    @Test
    void csvEmptySelectionReturnsNotFound() throws Exception {
        when(service.exportCsv(any(), eq(12L), eq(UserRole.EMPLOYEE)))
                .thenThrow(new org.involens_be.exception.NoInvoicesToExportException());
        mvc.perform(post("/api/v1/export-jobs/csv"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NO_INVOICES_TO_EXPORT"));
    }

    /** Reports CSV generation failures with a format-specific message. */
    @Test
    void csvFailureReturnsServerError() throws Exception {
        when(service.exportCsv(any(), eq(12L), eq(UserRole.EMPLOYEE)))
                .thenThrow(new org.involens_be.exception.InvoiceExportException("CSV", new java.io.IOException("failure")));
        mvc.perform(post("/api/v1/export-jobs/csv"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("INVOICE_EXPORT_FAILED"))
                .andExpect(jsonPath("$.error.message").value("Xuất file CSV thất bại. Vui lòng thử lại."));
    }

    /** Rejects unsupported CSV statuses before calling the export service. */
    @Test
    void csvUnsupportedStatusReturnsBadRequest() throws Exception {
        mvc.perform(post("/api/v1/export-jobs/csv").param("status", "REJECTED"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        verifyNoInteractions(service);
    }

    /** Returns attachment bytes and passes the preview selection and authenticated scope to the service. */
    @Test
    void excelReturnsDownloadAndBindsFilters() throws Exception {
        byte[] bytes = new byte[]{80, 75, 3, 4};
        when(service.exportExcel(any(), eq(12L), eq(UserRole.EMPLOYEE))).thenAnswer(invocation -> {
            ExportPreviewRequest request = invocation.getArgument(0);
            org.junit.jupiter.api.Assertions.assertEquals("2026-09-01", request.getDateFrom());
            org.junit.jupiter.api.Assertions.assertEquals("2026-09-30", request.getDateTo());
            org.junit.jupiter.api.Assertions.assertEquals(ExportPreviewRequest.Status.APPROVED, request.getStatus());
            return bytes;
        });
        mvc.perform(post("/api/v1/export-jobs/excel").param("dateFrom", "2026-09-01")
                        .param("dateTo", "2026-09-30").param("status", "APPROVED"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"invoices.xlsx\""))
                .andExpect(content().bytes(bytes));
    }

    /** Provides a JSON message the frontend can display as an alert for an empty export selection. */
    @Test
    void excelEmptySelectionReturnsAlertMessage() throws Exception {
        when(service.exportExcel(any(), eq(12L), eq(UserRole.EMPLOYEE)))
                .thenThrow(new org.involens_be.exception.NoInvoicesToExportException());
        mvc.perform(post("/api/v1/export-jobs/excel"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NO_INVOICES_TO_EXPORT"))
                .andExpect(jsonPath("$.error.message").value("Không có hóa đơn nào phù hợp với bộ lọc trong khoảng ngày bạn chọn."));
    }

    /** Reports server-side workbook failures through the existing JSON error envelope. */
    @Test
    void excelFailureReturnsServerError() throws Exception {
        when(service.exportExcel(any(), eq(12L), eq(UserRole.EMPLOYEE)))
                .thenThrow(new org.involens_be.exception.InvoiceExportException(new java.io.IOException("disk failure")));
        mvc.perform(post("/api/v1/export-jobs/excel"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("INVOICE_EXPORT_FAILED"));
    }

    /** Rejects unsupported export statuses before invoking business logic. */
    @Test
    void excelUnsupportedStatusReturnsBadRequest() throws Exception {
        mvc.perform(post("/api/v1/export-jobs/excel").param("status", "REJECTED"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        verifyNoInteractions(service);
    }

    /** Configures isolated MVC serialization with an employee JWT principal. */
    @BeforeEach
    void setUp() {
        service = mock(ExportJobService.class);
        mvc = MockMvcBuilders.standaloneSetup(new ExportJobController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build();
        Jwt jwt = Jwt.withTokenValue("test").header("alg", "HS256").subject("employee@example.com")
                .claim("userId", 12L).claim("role", "EMPLOYEE").build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(jwt, null, List.of()));
    }

    /** Clears the test principal after each HTTP contract test. */
    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
    }

    /** Preserves request filters and counts while returning invoice preview fields without an aggregate amount. */
    @Test
    void previewBindsFiltersAndReturnsApprovedCounts() throws Exception {
        when(service.preview(any(), eq(12L), eq(UserRole.EMPLOYEE))).thenAnswer(invocation -> {
            ExportPreviewRequest request = invocation.getArgument(0);
            org.junit.jupiter.api.Assertions.assertEquals("2026-09-01", request.getDateFrom());
            org.junit.jupiter.api.Assertions.assertEquals(ExportPreviewRequest.Status.APPROVED, request.getStatus());
            return ExportPreviewResponse.builder().totalInvoices(5L).approved(5L).pendingReview(null)
                    .items(List.of(ExportPreviewResponse.Item.builder()
                            .invoiceNumber("000123").sellerName("Supplier")
                            .invoiceDate(java.time.LocalDate.of(2026, 9, 15))
                            .totalAmount(new java.math.BigDecimal("125000.50"))
                            .status(ExportPreviewRequest.Status.APPROVED).build()))
                    .build();
        });

        mvc.perform(post("/api/v1/export-jobs/preview")
                        .param("dateFrom", "2026-09-01")
                        .param("dateTo", "2026-09-30")
                        .param("status", "APPROVED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(0))
                .andExpect(jsonPath("$.data.total_invoices").value(5))
                .andExpect(jsonPath("$.data.approved").value(5))
                .andExpect(jsonPath("$.data.pending_review").value(nullValue()))
                .andExpect(jsonPath("$.data.total_amount").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].invoice_number").value("000123"))
                .andExpect(jsonPath("$.data.items[0].seller_name").value("Supplier"))
                .andExpect(jsonPath("$.data.items[0].invoice_date").value("2026-09-15"))
                .andExpect(jsonPath("$.data.items[0].total_amount").value(125000.50))
                .andExpect(jsonPath("$.data.items[0].status").value("APPROVED"));
    }

    /** Returns a validation response when the requested export status is unsupported. */
    @Test
    void unsupportedStatusReturnsBadRequest() throws Exception {
        mvc.perform(post("/api/v1/export-jobs/preview").param("status", "REJECTED"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(1))
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        verifyNoInteractions(service);
    }
}
