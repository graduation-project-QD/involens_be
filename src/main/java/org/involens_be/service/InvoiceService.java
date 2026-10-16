package org.involens_be.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.involens_be.common.DocumentState;
import org.involens_be.common.UserRole;
import org.involens_be.dto.request.InvoiceListRequest;
import org.involens_be.dto.response.InvoiceListItemResponse;
import org.involens_be.dto.response.InvoiceListResponse;
import org.involens_be.repository.InvoiceRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;

/** Reads invoice outcomes for the dedicated extracted-invoice screen. */
@Slf4j
@Service
@RequiredArgsConstructor
public class InvoiceService {
    private final InvoiceRepository invoices;

    /** Lists selected extraction states for managers globally or employees within their uploads. */
    @Transactional(readOnly = true)
    public InvoiceListResponse listInvoices(InvoiceListRequest request, Long userId, UserRole role) {
        log.info("Listing extracted invoices. userId={}, role={}, state={}, page={}, pageSize={}",
                userId, role, request.getCurrent_state(), request.getPage(), request.getPage_size());
        List<DocumentState> states = request.getCurrent_state() == null
                ? List.of(DocumentState.EXTRACT_SUCCESS, DocumentState.REVIEW_REQUIRED, DocumentState.EXTRACT_FAILED)
                : List.of(request.getCurrent_state());
        var result = invoices.findVisibleInvoices(states, role == UserRole.MANAGER, userId, filenamePattern(request.getQ()),
                request.getDate_from(), request.getDate_to(), PageRequest.of(request.getPage() - 1, request.getPage_size()));
        var items = result.getContent().stream().map(this::toListItem).toList();
        log.info("Extracted invoices loaded. userId={}, total={}, returned={}", userId, result.getTotalElements(), items.size());
        return InvoiceListResponse.builder().items(items).total(result.getTotalElements())
                .page(request.getPage()).pageSize(request.getPage_size()).build();
    }

    /** Prepares a literal case-insensitive filename substring, ignoring an omitted or blank search. */
    private String filenamePattern(String query) {
        log.debug("Preparing invoice filename search. filtered={}", query != null && !query.isBlank());
        if (query == null || query.isBlank()) {
            return null;
        }
        return "%" + query.trim().toLowerCase(Locale.ROOT)
                .replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    }

    /** Exposes invoice fields and document errors, preserving missing values and normalizing upload time to UTC. */
    private InvoiceListItemResponse toListItem(InvoiceRepository.InvoiceListRow row) {
        log.debug("Mapping extracted invoice list item. documentId={}, state={}", row.documentId(), row.currentState());
        return InvoiceListItemResponse.builder().id(row.id()).documentId(row.documentId())
                .originalFilename(row.originalFilename()).sellerName(row.sellerName())
                .invoiceNumber(row.invoiceNumber()).invoiceDate(row.invoiceDate())
                .totalAmount(row.totalAmount()).currency(row.currency()).uploadedByName(row.uploadedByName())
                .uploadedAt(row.uploadedAt() == null ? null : row.uploadedAt().withOffsetSameInstant(ZoneOffset.UTC))
                .currentState(row.currentState()).errorCode(row.errorCode()).errorMessage(row.errorMessage()).build();
    }
}
