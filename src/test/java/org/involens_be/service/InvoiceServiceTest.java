package org.involens_be.service;

import lombok.extern.slf4j.Slf4j;
import org.involens_be.common.DocumentState;
import org.involens_be.common.UserRole;
import org.involens_be.dto.request.InvoiceListRequest;
import org.involens_be.repository.InvoiceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Verifies invoice list scoping, pagination and nullable failed extraction data. */
@Slf4j
class InvoiceServiceTest {
    /** Passes invoice date bounds together with filename, state and ownership without converting calendar dates. */
    @Test
    void combinesInvoiceDateBoundsWithOtherFilters() {
        log.info("Testing combined invoice date filters");
        var repository = mock(InvoiceRepository.class);
        var request = new InvoiceListRequest();
        request.setQ("Bill");
        request.setCurrent_state(DocumentState.EXTRACT_SUCCESS);
        LocalDate from = LocalDate.of(2026, 10, 1);
        LocalDate to = LocalDate.of(2026, 10, 15);
        request.setDate_from(from);
        request.setDate_to(to);
        var states = List.of(DocumentState.EXTRACT_SUCCESS);
        var pageable = PageRequest.of(0, 8);
        when(repository.findVisibleInvoices(states, false, 12L, "%bill%", from, to, pageable))
                .thenReturn(new PageImpl<>(List.of()));
        assertTrue(new InvoiceService(repository).listInvoices(request, 12L, UserRole.EMPLOYEE).getItems().isEmpty());
        verify(repository).findVisibleInvoices(states, false, 12L, "%bill%", from, to, pageable);
    }

    /** Combines a literal filename search with employee ownership, state filtering and pagination. */
    @Test
    void searchesLiteralFilename() {
        log.info("Testing trimmed case-insensitive literal invoice filename search");
        var repository = mock(InvoiceRepository.class);
        var request = new InvoiceListRequest();
        request.setQ("  BILL_50%!  ");
        request.setCurrent_state(DocumentState.EXTRACT_SUCCESS);
        request.setPage(2);
        request.setPage_size(10);
        var states = List.of(DocumentState.EXTRACT_SUCCESS);
        var pageable = PageRequest.of(1, 10);
        when(repository.findVisibleInvoices(states, false, 12L, "%bill!_50!%!!%", null, null, pageable))
                .thenReturn(new PageImpl<>(List.of(), pageable, 0));
        var response = new InvoiceService(repository).listInvoices(request, 12L, UserRole.EMPLOYEE);
        assertEquals(0, response.getTotal());
        assertTrue(response.getItems().isEmpty());
        verify(repository).findVisibleInvoices(states, false, 12L, "%bill!_50!%!!%", null, null, pageable);
    }

    /** Ignores empty and whitespace-only filename searches so normal invoice listing still works. */
    @Test
    void ignoresBlankSearch() {
        log.info("Testing blank invoice filename searches");
        var repository = mock(InvoiceRepository.class);
        var states = List.of(DocumentState.EXTRACT_SUCCESS, DocumentState.REVIEW_REQUIRED, DocumentState.EXTRACT_FAILED);
        when(repository.findVisibleInvoices(states, true, 99L, null, null, null, PageRequest.of(0, 8)))
                .thenReturn(new PageImpl<>(List.of()));
        for (String query : List.of("", "   ", "\t")) {
            var request = new InvoiceListRequest();
            request.setQ(query);
            assertTrue(new InvoiceService(repository).listInvoices(request, 99L, UserRole.MANAGER).getItems().isEmpty());
        }
        verify(repository, times(3)).findVisibleInvoices(states, true, 99L, null, null, null, PageRequest.of(0, 8));
    }

    /** Requests all three extraction outcomes globally for a manager and retains failed documents without invoice data. */
    @Test
    void managerListsAllOutcomesIncludingFailure() {
        log.info("Testing manager invoice outcome visibility");
        var repository = mock(InvoiceRepository.class);
        var request = new InvoiceListRequest();
        request.setPage(2);
        var pageable = PageRequest.of(1, 8);
        UUID documentId = UUID.randomUUID();
        var row = new InvoiceRepository.InvoiceListRow(null, documentId, "failed.pdf", null, null,
                null, null, null, "Manager", OffsetDateTime.parse("2026-10-15T14:00:00+07:00"),
                DocumentState.EXTRACT_FAILED, "IMAGE_DOWNLOAD_FAILED", "MinIO unavailable");
        var states = List.of(DocumentState.EXTRACT_SUCCESS, DocumentState.REVIEW_REQUIRED, DocumentState.EXTRACT_FAILED);
        when(repository.findVisibleInvoices(states, true, 99L, null, null, null, pageable))
                .thenReturn(new PageImpl<>(List.of(row), pageable, 9));

        var response = new InvoiceService(repository).listInvoices(request, 99L, UserRole.MANAGER);

        assertEquals(9, response.getTotal());
        assertEquals(2, response.getPage());
        assertEquals(8, response.getPageSize());
        var item = response.getItems().getFirst();
        assertNull(item.getId());
        assertNull(item.getSellerName());
        assertEquals(documentId, item.getDocumentId());
        assertEquals(DocumentState.EXTRACT_FAILED, item.getCurrentState());
        assertEquals("IMAGE_DOWNLOAD_FAILED", item.getErrorCode());
        assertEquals("MinIO unavailable", item.getErrorMessage());
        assertEquals(OffsetDateTime.parse("2026-10-15T07:00:00Z"), item.getUploadedAt());
        verify(repository).findVisibleInvoices(states, true, 99L, null, null, null, pageable);
    }

    /** Limits an employee query to their uploader ID and requested review state while preserving seller and amounts. */
    @Test
    void employeeFiltersReviewInvoices() {
        log.info("Testing employee invoice ownership and review filter");
        var repository = mock(InvoiceRepository.class);
        var request = new InvoiceListRequest();
        request.setCurrent_state(DocumentState.REVIEW_REQUIRED);
        UUID invoiceId = UUID.randomUUID();
        var row = new InvoiceRepository.InvoiceListRow(invoiceId, UUID.randomUUID(), "review.pdf", "Seller",
                "INV-1", LocalDate.of(2026, 10, 1), new BigDecimal("50000.00"), "VND", "Employee",
                null, DocumentState.REVIEW_REQUIRED, null, null);
        when(repository.findVisibleInvoices(List.of(DocumentState.REVIEW_REQUIRED), false, 12L, null, null, null, PageRequest.of(0, 8)))
                .thenReturn(new PageImpl<>(List.of(row)));

        var response = new InvoiceService(repository).listInvoices(request, 12L, UserRole.EMPLOYEE);

        var item = response.getItems().getFirst();
        assertEquals(invoiceId, item.getId());
        assertEquals("Seller", item.getSellerName());
        assertEquals("INV-1", item.getInvoiceNumber());
        assertEquals(LocalDate.of(2026, 10, 1), item.getInvoiceDate());
        assertEquals(new BigDecimal("50000.00"), item.getTotalAmount());
        assertEquals("VND", item.getCurrency());
        assertNull(item.getUploadedAt());
        verify(repository).findVisibleInvoices(List.of(DocumentState.REVIEW_REQUIRED), false, 12L, null, null, null, PageRequest.of(0, 8));
    }

    /** Returns a successful empty page when no visible document matches the success filter. */
    @Test
    void returnsEmptyPage() {
        log.info("Testing empty invoice list");
        var repository = mock(InvoiceRepository.class);
        var request = new InvoiceListRequest();
        request.setCurrent_state(DocumentState.EXTRACT_SUCCESS);
        when(repository.findVisibleInvoices(List.of(DocumentState.EXTRACT_SUCCESS), false, 12L, null, null, null, PageRequest.of(0, 8)))
                .thenReturn(new PageImpl<>(List.of()));
        var response = new InvoiceService(repository).listInvoices(request, 12L, UserRole.EMPLOYEE);
        assertTrue(response.getItems().isEmpty());
        assertEquals(0, response.getTotal());
    }
}
