package org.involens_be.controller;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.involens_be.common.UserRole;
import org.involens_be.dto.response.VendorItemReportResponse;
import org.involens_be.exception.GlobalExceptionHandler;
import org.involens_be.exception.InvalidVendorItemReportException;
import org.involens_be.service.VendorItemReportService;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.math.BigDecimal;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

@Slf4j
class VendorItemReportControllerTest {
    private final VendorItemReportService service = mock(VendorItemReportService.class);
    private MockMvc mvc;

    /** Configures report HTTP tests with a trusted manager identity and application exception handlers. */
    @BeforeEach
    void setUp() {
        log.info("Setting up supplier report HTTP tests");
        mvc = MockMvcBuilders.standaloneSetup(new VendorItemReportController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build();
        var jwt = Jwt.withTokenValue("test").header("alg", "HS256").subject("manager")
                .claim("userId", 12L).claim("role", "MANAGER").build();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(jwt, null, List.of()));
    }

    /** Removes the test caller after each report request scenario. */
    @AfterEach
    void tearDown() {
        log.debug("Clearing supplier report test principal");
        SecurityContextHolder.clearContext();
    }

    /** Verifies query binding, manager scope and the empty JSON report contract. */
    @Test
    void returnsReport() throws Exception {
        log.info("Testing report JSON contract");
        when(service.report(any(), eq(12L), eq(UserRole.MANAGER))).thenAnswer(invocation -> {
            org.involens_be.dto.request.VendorItemReportRequest request = invocation.getArgument(0);
            assertEquals(3L, request.getVendorId());
            assertEquals("2026-01-01", request.getDateFrom());
            assertEquals("2026-01-31", request.getDateTo());
            return new VendorItemReportResponse(BigDecimal.ZERO, 0, 0, List.of());
        });
        mvc.perform(get("/api/v1/reports/vendor-items").param("vendorId", "3")
                        .param("dateFrom", "2026-01-01").param("dateTo", "2026-01-31"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total_revenue").value(0))
                .andExpect(jsonPath("$.data.total_invoices").value(0))
                .andExpect(jsonPath("$.data.total_items").value(0)).andExpect(jsonPath("$.data.items").isEmpty());
    }

    /** Verifies attachment headers and the required-filter error envelope for Excel downloads. */
    @Test
    void downloadsExcelAndReportsInvalidFilters() throws Exception {
        log.info("Testing report Excel HTTP contract");
        byte[] bytes = {80, 75, 3, 4};
        when(service.exportExcel(any(), eq(12L), eq(UserRole.MANAGER))).thenReturn(bytes);
        mvc.perform(get("/api/v1/reports/vendor-items/excel").param("vendorId", "3")
                        .param("dateFrom", "2026-01-01").param("dateTo", "2026-01-31"))
                .andExpect(status().isOk()).andExpect(content().bytes(bytes))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"vendor-items.xlsx\""));
        when(service.exportExcel(any(), eq(12L), eq(UserRole.MANAGER)))
                .thenThrow(new InvalidVendorItemReportException("vendorId is required"));
        mvc.perform(get("/api/v1/reports/vendor-items/excel"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }
}
