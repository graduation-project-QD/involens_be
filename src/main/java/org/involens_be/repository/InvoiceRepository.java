package org.involens_be.repository;

import org.involens_be.entity.InvoiceEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;
import java.util.List;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import org.involens_be.common.DocumentState;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InvoiceRepository extends JpaRepository<InvoiceEntity, UUID> {

    /** Finds the invoice populated from a specific AI extraction result. */
    Optional<InvoiceEntity> findByExtractionResultId(UUID extractionResultId);

    /** Pages visible active outcomes matching filename, uploader or supplier, retaining missing invoice data. */
    @Query(value = """
            select new org.involens_be.repository.InvoiceRepository$InvoiceListRow(
                i.id, d.id, d.originalFilename, i.sellerName, i.invoiceNumber, i.invoiceDate,
                i.totalAmount, i.currency, u.fullName, d.uploadedAt, d.currentState, d.errorCode, d.errorMessage)
            from DocumentEntity d
            left join InvoiceEntity i on i.documentId = d.id
            left join UserEntity u on u.id = d.uploadedById
            where d.status = org.involens_be.common.CommonStatus.ACTIVE
              and d.currentState in :states
              and (:manager = true or d.uploadedById = :userId)
              and (:keywordPattern is null
                    or lower(d.originalFilename) like :keywordPattern escape '!'
                    or lower(u.fullName) like :keywordPattern escape '!'
                    or lower(i.sellerName) like :keywordPattern escape '!')
              and (:dateFrom is null or i.invoiceDate >= :dateFrom)
              and (:dateTo is null or i.invoiceDate <= :dateTo)
            order by d.uploadedAt desc, d.id desc
            """, countQuery = """
            select count(d.id) from DocumentEntity d
            left join InvoiceEntity i on i.documentId = d.id
            left join UserEntity u on u.id = d.uploadedById
            where d.status = org.involens_be.common.CommonStatus.ACTIVE
              and d.currentState in :states
              and (:manager = true or d.uploadedById = :userId)
              and (:keywordPattern is null
                    or lower(d.originalFilename) like :keywordPattern escape '!'
                    or lower(u.fullName) like :keywordPattern escape '!'
                    or lower(i.sellerName) like :keywordPattern escape '!')
              and (:dateFrom is null or i.invoiceDate >= :dateFrom)
              and (:dateTo is null or i.invoiceDate <= :dateTo)
            """)
    Page<InvoiceListRow> findVisibleInvoices(@Param("states") List<DocumentState> states,
            @Param("manager") boolean manager, @Param("userId") Long userId,
            @Param("keywordPattern") String keywordPattern, @Param("dateFrom") LocalDate dateFrom,
            @Param("dateTo") LocalDate dateTo, Pageable pageable);

    /** Carries nullable invoice data alongside the document's authoritative processing state. */
    record InvoiceListRow(UUID id, UUID documentId, String originalFilename, String sellerName,
            String invoiceNumber, LocalDate invoiceDate, BigDecimal totalAmount, String currency,
            String uploadedByName, OffsetDateTime uploadedAt, DocumentState currentState,
            String errorCode, String errorMessage) { }
}
