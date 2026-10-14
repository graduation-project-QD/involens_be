package org.involens_be.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.involens_be.common.UserRole;
import org.involens_be.dto.request.ExportPreviewRequest;
import org.involens_be.dto.response.ExportPreviewResponse;
import org.involens_be.exception.InvalidExportPreviewFilterException;
import org.involens_be.repository.ExportJobRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import org.springframework.data.domain.PageRequest;
import org.involens_be.exception.InvoiceExportException;
import org.involens_be.exception.NoInvoicesToExportException;
import java.io.IOException;

@Slf4j(topic = "EXPORT-JOB-SERVICE")
@Service
@RequiredArgsConstructor
public class ExportJobService {

    private final ExportJobRepository exportJobRepository;
    private final InvoiceExcelWriter invoiceExcelWriter;

    /** Downloads all visible invoices matching the preview selection as an accounting workbook. */
    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public byte[] exportExcel(ExportPreviewRequest request, Long userId, UserRole role) {
        log.info("Starting invoice Excel export. userId={}, role={}, dateFrom={}, dateTo={}, status={}",
                userId, role, request.getDateFrom(), request.getDateTo(), request.getStatus());
        LocalDate dateFrom = parseDate(request.getDateFrom(), "dateFrom");
        LocalDate dateTo = parseDate(request.getDateTo(), "dateTo");
        validateDateRange(dateFrom, dateTo);
        boolean manager = role == UserRole.MANAGER;
        boolean approvedOnly = request.getStatus() == ExportPreviewRequest.Status.APPROVED;
        boolean pendingOnly = request.getStatus() == ExportPreviewRequest.Status.PENDING_REVIEW;
        try {
            byte[] result = invoiceExcelWriter.write(batch -> {
                log.debug("Loading invoice export batch. userId={}, batch={}", userId, batch);
                return exportJobRepository.findExportBatch(dateFrom, dateTo, approvedOnly, pendingOnly,
                        manager, userId, PageRequest.of(batch, 1000));
            });
            return result;
        } catch (NoInvoicesToExportException exception) {
            log.warn("No invoices matched export selection. userId={}", userId);
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new InvoiceExportException(exception);
        }
    }

    /** Builds invoice counts for an export preview within the caller's role-based visibility scope. */
    @Transactional(readOnly = true)
    public ExportPreviewResponse preview(ExportPreviewRequest request, Long userId, UserRole role) {
        log.info("Building export preview. userId={}, role={}, dateFrom={}, dateTo={}, status={}",
                userId, role, request.getDateFrom(), request.getDateTo(), request.getStatus());
        LocalDate dateFrom = parseDate(request.getDateFrom(), "dateFrom");
        LocalDate dateTo = parseDate(request.getDateTo(), "dateTo");
        validateDateRange(dateFrom, dateTo);
        boolean manager = role == UserRole.MANAGER;
        boolean approvedOnly = request.getStatus() == ExportPreviewRequest.Status.APPROVED;
        boolean pendingOnly = request.getStatus() == ExportPreviewRequest.Status.PENDING_REVIEW;
        var counts = exportJobRepository.countPreview(dateFrom, dateTo,
                approvedOnly, pendingOnly, manager, userId);
        boolean includeApproved = request.getStatus() != ExportPreviewRequest.Status.PENDING_REVIEW;
        boolean includePending = request.getStatus() != ExportPreviewRequest.Status.APPROVED;
        var response = ExportPreviewResponse.builder()
                .totalInvoices(counts.totalInvoices())
                .approved(includeApproved ? counts.approved() : null)
                .pendingReview(includePending ? counts.pendingReview() : null)
                .build();
        return response;
    }

    /** Ensures the inclusive invoice-date range is chronologically valid when both bounds are supplied. */
    private void validateDateRange(LocalDate dateFrom, LocalDate dateTo) {
        if (dateFrom != null && dateTo != null && dateFrom.isAfter(dateTo)) {
            log.warn("Rejecting export preview with reversed date range. dateFrom={}, dateTo={}",
                    dateFrom, dateTo);
            throw new InvalidExportPreviewFilterException("dateFrom must be on or before dateTo");
        }
        log.debug("Export preview date range validated. dateFrom={}, dateTo={}",
                dateFrom, dateTo);
    }

    /** Converts an optional ISO invoice-date filter, treating a literal null as an omitted query value. */
    private LocalDate parseDate(String value, String parameterName) {
        if (value == null || value.isBlank() || "null".equalsIgnoreCase(value)) {
            log.debug("Export preview date filter is not supplied. parameter={}", parameterName);
            return null;
        }
        try {
            LocalDate parsedDate = LocalDate.parse(value);
            log.debug("Export preview date filter parsed. parameter={}, value={}", parameterName, parsedDate);
            return parsedDate;
        } catch (DateTimeParseException exception) {
            log.warn("Rejecting malformed export preview date. parameter={}, value={}", parameterName, value);
            throw new InvalidExportPreviewFilterException(parameterName + " must use YYYY-MM-DD format");
        }
    }
}
