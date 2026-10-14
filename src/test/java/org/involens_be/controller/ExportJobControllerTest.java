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

    /** Binds camel-case filters and returns null for the unselected status group. */
    @Test
    void previewBindsFiltersAndReturnsApprovedCounts() throws Exception {
        when(service.preview(any(), eq(12L), eq(UserRole.EMPLOYEE))).thenAnswer(invocation -> {
            ExportPreviewRequest request = invocation.getArgument(0);
            org.junit.jupiter.api.Assertions.assertEquals("2026-09-01", request.getDateFrom());
            org.junit.jupiter.api.Assertions.assertEquals(ExportPreviewRequest.Status.APPROVED, request.getStatus());
            return ExportPreviewResponse.builder().totalInvoices(5L).approved(5L).pendingReview(null).build();
        });

        mvc.perform(post("/api/v1/export-jobs/preview")
                        .param("dateFrom", "2026-09-01")
                        .param("dateTo", "2026-09-30")
                        .param("status", "APPROVED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(0))
                .andExpect(jsonPath("$.data.total_invoices").value(5))
                .andExpect(jsonPath("$.data.approved").value(5))
                .andExpect(jsonPath("$.data.pending_review").value(nullValue()));
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
