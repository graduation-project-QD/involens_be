package org.involens_be.repository;

import java.time.LocalDate;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.involens_be.entity.InvoiceEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface VendorItemReportRepository extends JpaRepository<InvoiceEntity, UUID> {
    /** Loads successful visible invoices, retaining invoices without items for the invoice count. */
    @Query("""
            select new org.involens_be.repository.VendorItemReportRepository$ReportRow(
                i.id, item.id, item.itemName, item.quantity, item.unitPrice, item.lineTotal)
            from InvoiceEntity i
            join DocumentEntity d on d.id = i.documentId
            left join InvoiceItemEntity item on item.invoiceId = i.id
            where d.status = org.involens_be.common.CommonStatus.ACTIVE
              and d.currentState = org.involens_be.common.DocumentState.EXTRACT_SUCCESS
              and (:manager = true or d.uploadedById = :userId)
              and i.vendorId = :vendorId
              and i.invoiceDate >= :dateFrom and i.invoiceDate <= :dateTo
            order by i.id, item.id
            """)
    List<ReportRow> findReportRows(@Param("vendorId") Long vendorId,
            @Param("dateFrom") LocalDate dateFrom, @Param("dateTo") LocalDate dateTo,
            @Param("manager") boolean manager, @Param("userId") Long userId);

    record ReportRow(UUID invoiceId, UUID itemId, String itemName, Float quantity,
                     BigDecimal unitPrice, BigDecimal lineTotal) {
    }
}
