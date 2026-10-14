package org.involens_be.repository;

import org.involens_be.entity.InvoiceEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.UUID;
import java.util.List;
import org.involens_be.common.DocumentState;
import org.springframework.data.domain.Pageable;

public interface ExportJobRepository extends JpaRepository<InvoiceEntity, UUID> {

    /** Loads one ordered batch of invoices using the same filters and visibility as export preview. */
    @Query("""
            select new org.involens_be.repository.ExportJobRepository$InvoiceExportRow(
                i.id, i.invoiceNumber, i.invoiceDate, i.sellerName, i.taxCode, i.sellerAddress,
                i.totalAmount, i.currency, d.currentState, i.approvedAt)
            from InvoiceEntity i
            join DocumentEntity d on d.id = i.documentId
            where d.status = org.involens_be.common.CommonStatus.ACTIVE
              and d.currentState in (
                    org.involens_be.common.DocumentState.EXTRACT_SUCCESS,
                    org.involens_be.common.DocumentState.REVIEW_REQUIRED)
              and (:manager = true or d.uploadedById = :userId)
              and (cast(:dateFrom as date) is null or i.invoiceDate >= :dateFrom)
              and (cast(:dateTo as date) is null or i.invoiceDate <= :dateTo)
              and (:approvedOnly = false
                    or d.currentState = org.involens_be.common.DocumentState.EXTRACT_SUCCESS)
              and (:pendingOnly = false
                    or d.currentState = org.involens_be.common.DocumentState.REVIEW_REQUIRED)
            order by i.invoiceDate, i.id
            """)
    List<InvoiceExportRow> findExportBatch(
            @Param("dateFrom") LocalDate dateFrom,
            @Param("dateTo") LocalDate dateTo,
            @Param("approvedOnly") boolean approvedOnly,
            @Param("pendingOnly") boolean pendingOnly,
            @Param("manager") boolean manager,
            @Param("userId") Long userId,
            Pageable pageable);

    /** Carries an invoice and its document state for the accounting workbook. */
    record InvoiceExportRow(UUID id, String invoiceNumber, LocalDate invoiceDate, String sellerName,
                            String taxCode, String sellerAddress, java.math.BigDecimal totalAmount,
                            String currency, DocumentState state, java.time.LocalDateTime approvedAt) {
    }

    /** Counts visible invoices by their document extraction state for export preview. */
    @Query("""
            select new org.involens_be.repository.ExportJobRepository$PreviewCounts(
                count(i.id),
                coalesce(sum(case when d.currentState = org.involens_be.common.DocumentState.EXTRACT_SUCCESS
                    then 1L else 0L end), 0L),
                coalesce(sum(case when d.currentState = org.involens_be.common.DocumentState.REVIEW_REQUIRED
                    then 1L else 0L end), 0L))
            from InvoiceEntity i
            join DocumentEntity d on d.id = i.documentId
            where d.status = org.involens_be.common.CommonStatus.ACTIVE
              and d.currentState in (
                    org.involens_be.common.DocumentState.EXTRACT_SUCCESS,
                    org.involens_be.common.DocumentState.REVIEW_REQUIRED)
              and (:manager = true or d.uploadedById = :userId)
              and (cast(:dateFrom as date) is null or i.invoiceDate >= :dateFrom)
              and (cast(:dateTo as date) is null or i.invoiceDate <= :dateTo)
              and (:approvedOnly = false
                    or d.currentState = org.involens_be.common.DocumentState.EXTRACT_SUCCESS)
              and (:pendingOnly = false
                    or d.currentState = org.involens_be.common.DocumentState.REVIEW_REQUIRED)
            """)
    PreviewCounts countPreview(
            @Param("dateFrom") LocalDate dateFrom,
            @Param("dateTo") LocalDate dateTo,
            @Param("approvedOnly") boolean approvedOnly,
            @Param("pendingOnly") boolean pendingOnly,
            @Param("manager") boolean manager,
            @Param("userId") Long userId);

    /** Carries aggregate invoice totals returned by the export-preview query. */
    record PreviewCounts(Long totalInvoices, Long approved, Long pendingReview) {
    }
}
