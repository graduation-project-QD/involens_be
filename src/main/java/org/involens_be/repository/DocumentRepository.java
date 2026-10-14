package org.involens_be.repository;

import org.involens_be.entity.DocumentEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface DocumentRepository extends JpaRepository<DocumentEntity, UUID>,
        org.springframework.data.jpa.repository.JpaSpecificationExecutor<DocumentEntity> {

    /** Locks the submitted document while its AI result and related invoice are persisted atomically. */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select d from DocumentEntity d where d.id = :documentId")
    java.util.Optional<DocumentEntity> findByIdForExtractionUpdate(
            @org.springframework.data.repository.query.Param("documentId") UUID documentId);

    /** Serializes duplicate checks for one checksum until the current database transaction completes. */
    @org.springframework.data.jpa.repository.Query(
            value = "select 1 from pg_advisory_xact_lock(hashtextextended(:checksum, 0))",
            nativeQuery = true)
    Integer acquireChecksumLock(
            @org.springframework.data.repository.query.Param("checksum") String checksum);

    /** Checks whether the same file content is already active and successfully uploaded system-wide. */
    boolean existsByChecksumSha256AndStatusAndUploadStatus(
            String checksumSha256,
            org.involens_be.common.CommonStatus status,
            org.involens_be.common.UploadStatus uploadStatus);

    /**
     * Updates dispatch state and error details only while the document matches the expected state.
     * Commits independently so extraction dispatch after the upload commit can persist the transition safely.
     */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.transaction.annotation.Transactional(
            propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    @org.springframework.data.jpa.repository.Query("""
            update DocumentEntity d set d.currentState = :newState,
                d.errorCode = :errorCode, d.errorMessage = :errorMessage
            where d.id = :documentId and d.currentState = :expectedState
            """)
    int updateDispatchStateIfMatching(
            @org.springframework.data.repository.query.Param("documentId") UUID documentId,
            @org.springframework.data.repository.query.Param("expectedState") org.involens_be.common.DocumentState expectedState,
            @org.springframework.data.repository.query.Param("newState") org.involens_be.common.DocumentState newState,
            @org.springframework.data.repository.query.Param("errorCode") String errorCode,
            @org.springframework.data.repository.query.Param("errorMessage") String errorMessage);

    /** Counts active documents by processing state within the authenticated user's visibility scope. */
    @org.springframework.data.jpa.repository.Query("""
            select d.currentState, count(d) from DocumentEntity d
            where d.status = :status and (:global = true or d.uploadedById = :ownerId)
            group by d.currentState
            """)
    java.util.List<Object[]> countByState(
            @org.springframework.data.repository.query.Param("status") org.involens_be.common.CommonStatus status,
            @org.springframework.data.repository.query.Param("global") boolean global,
            @org.springframework.data.repository.query.Param("ownerId") Long ownerId);

    /** Finds an active document without applying uploader ownership restrictions. */
    java.util.Optional<DocumentEntity> findByIdAndStatus(UUID id, org.involens_be.common.CommonStatus status);

    /** Finds an active document only when it belongs to the authenticated owner. */
    java.util.Optional<DocumentEntity> findByIdAndUploadedByIdAndStatus(
            UUID id, Long uploadedById, org.involens_be.common.CommonStatus status);

    /** Locks an active document visible to the caller until its extraction retry transaction completes. */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("""
            select d from DocumentEntity d
            where d.id = :documentId
              and d.status = org.involens_be.common.CommonStatus.ACTIVE
              and (:manager = true or d.uploadedById = :userId)
            """)
    java.util.Optional<DocumentEntity> findVisibleDocumentForUpdate(
            @org.springframework.data.repository.query.Param("documentId") UUID documentId,
            @org.springframework.data.repository.query.Param("userId") Long userId,
            @org.springframework.data.repository.query.Param("manager") boolean manager);
}
