package org.involens_be.repository;

import org.involens_be.entity.InvoiceItemEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface InvoiceItemRepository extends JpaRepository<InvoiceItemEntity, UUID> {
    /** Loads the invoice lines without imposing a display order. */
    List<InvoiceItemEntity> findAllByInvoiceId(UUID invoiceId);

    /** Removes the previous lines when a reviewer replaces an invoice's complete item list. */
    void deleteAllByInvoiceId(UUID invoiceId);
}
