package org.involens_be.repository;

import org.involens_be.entity.ExtractionResultEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ExtractionResultRepository extends JpaRepository<ExtractionResultEntity, UUID> {

    /** Finds the single AI extraction result associated with a document. */
    Optional<ExtractionResultEntity> findByDocumentId(UUID documentId);
}
