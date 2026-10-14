package org.involens_be.repository;

import org.involens_be.entity.InvoiceEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface InvoiceRepository extends JpaRepository<InvoiceEntity, UUID> {

    /** Finds the invoice populated from a specific AI extraction result. */
    Optional<InvoiceEntity> findByExtractionResultId(UUID extractionResultId);
}
