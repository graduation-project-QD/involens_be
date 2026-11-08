package org.involens_be.service;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.involens_be.common.UserRole;
import org.involens_be.dto.request.VendorItemReportRequest;
import org.involens_be.exception.InvalidVendorItemReportException;
import org.involens_be.repository.VendorItemReportRepository;
import org.involens_be.repository.VendorItemReportRepository.ReportRow;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Slf4j
class VendorItemReportServiceTest {
    private final VendorItemReportRepository repository = mock(VendorItemReportRepository.class);
    private final VendorItemReportExcelWriter writer = mock(VendorItemReportExcelWriter.class);
    private final VendorItemReportService service = new VendorItemReportService(repository, writer);

    /** Verifies grouping, weighted prices, rounding, ordering and counting invoices with no items. */
    @Test
    void aggregatesSuccessfulSelection() {
        log.info("Testing supplier report aggregation");
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        when(repository.findReportRows(3L, LocalDate.parse("2026-01-01"), LocalDate.parse("2026-01-31"), false, 12L))
                .thenReturn(List.of(
                        new ReportRow(first, UUID.randomUUID(), " Rice ", 2f, new BigDecimal("10"), new BigDecimal("20")),
                        new ReportRow(second, UUID.randomUUID(), "rice", 1f, new BigDecimal("16"), new BigDecimal("16")),
                        new ReportRow(first, UUID.randomUUID(), "Apple", 1f, new BigDecimal("36"), new BigDecimal("36")),
                        new ReportRow(UUID.randomUUID(), null, null, null, null, null)));
        var result = service.report(request(), 12L, UserRole.EMPLOYEE);
        assertEquals(3, result.totalInvoices());
        assertEquals(2, result.totalItems());
        assertEquals(new BigDecimal("72"), result.totalRevenue());
        assertEquals("Apple", result.items().getFirst().itemName());
        var rice = result.items().get(1);
        assertEquals(new BigDecimal("3.0"), rice.quantity());
        assertEquals(new BigDecimal("12.00"), rice.unitPrice());
        assertEquals(new BigDecimal("10"), rice.minUnitPrice());
        assertEquals(new BigDecimal("16"), rice.maxUnitPrice());
        assertEquals(new BigDecimal("50.0"), rice.percentage());
    }

    /** Confirms managers request all uploads and an empty selection still produces zero totals. */
    @Test
    void managerAndEmptySelection() {
        log.info("Testing manager report scope");
        when(repository.findReportRows(anyLong(), any(), any(), eq(true), eq(12L))).thenReturn(List.of());
        var result = service.report(request(), 12L, UserRole.MANAGER);
        assertEquals(0, result.totalInvoices());
        assertEquals(0, result.totalItems());
        assertEquals(BigDecimal.ZERO, result.totalRevenue());
        verify(repository).findReportRows(3L, LocalDate.parse("2026-01-01"), LocalDate.parse("2026-01-31"), true, 12L);
    }

    /** Rejects missing, malformed and reversed filters before issuing a database query. */
    @Test
    void rejectsInvalidFilters() {
        log.info("Testing required supplier report filters");
        var request = request();
        request.setVendorId(null);
        assertThrows(InvalidVendorItemReportException.class, () -> service.report(request, 12L, UserRole.EMPLOYEE));
        request.setVendorId(3L);
        request.setDateFrom(null);
        assertThrows(InvalidVendorItemReportException.class, () -> service.report(request, 12L, UserRole.EMPLOYEE));
        request.setDateFrom("bad");
        assertThrows(InvalidVendorItemReportException.class, () -> service.report(request, 12L, UserRole.EMPLOYEE));
        request.setDateFrom("2026-02-01");
        assertThrows(InvalidVendorItemReportException.class, () -> service.report(request, 12L, UserRole.EMPLOYEE));
        verifyNoInteractions(repository);
    }

    /** Uses a zero share for valid zero-value invoice lines and exports the same report object. */
    @Test
    void zeroRevenueAndExcelShareSelection() throws Exception {
        log.info("Testing zero revenue export");
        when(repository.findReportRows(anyLong(), any(), any(), anyBoolean(), anyLong())).thenReturn(List.of(
                new ReportRow(UUID.randomUUID(), UUID.randomUUID(), "Gift", 1f, BigDecimal.ZERO, BigDecimal.ZERO)));
        var request = request();
        when(writer.write(eq(request), any())).thenAnswer(invocation -> {
            org.involens_be.dto.response.VendorItemReportResponse report = invocation.getArgument(1);
            assertEquals(BigDecimal.ZERO, report.items().getFirst().percentage());
            return new byte[]{1, 2};
        });
        assertArrayEquals(new byte[]{1, 2}, service.exportExcel(request, 12L, UserRole.EMPLOYEE));
    }

    /** Supplies the selected supplier and inclusive invoice dates for report scenarios. */
    private VendorItemReportRequest request() {
        log.debug("Building report filter fixture");
        var request = new VendorItemReportRequest();
        request.setVendorId(3L);
        request.setDateFrom("2026-01-01");
        request.setDateTo("2026-01-31");
        return request;
    }
}
