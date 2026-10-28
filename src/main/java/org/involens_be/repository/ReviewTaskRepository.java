package org.involens_be.repository;

import org.involens_be.common.ReviewStatus;
import org.involens_be.entity.ReviewTaskEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.List;

public interface ReviewTaskRepository extends JpaRepository<ReviewTaskEntity, UUID> {

    /** Finds every review task associated with the extraction result being confirmed. */
    List<ReviewTaskEntity> findAllByExtractionResultId(UUID extractionResultId);

    /** Pages review tasks matching filename, uploader and upload dates under the caller's ownership scope. */
    @Query(value = """
            select new org.involens_be.repository.ReviewTaskRepository$ReviewTaskRow(
                rt.id, d.id, d.originalFilename, i.sellerName, rt.createdAt, rt.reason, rt.status, rt.priority)
            from ReviewTaskEntity rt
            join ExtractionResultEntity er on er.id = rt.extractionResultId
            join DocumentEntity d on d.id = er.documentId
            left join UserEntity u on u.id = d.uploadedById
            left join InvoiceEntity i on i.extractionResultId = er.id
            where d.status = org.involens_be.common.CommonStatus.ACTIVE
              and d.currentState = org.involens_be.common.DocumentState.REVIEW_REQUIRED
              and (:status is null or rt.status = :status)
              and (:manager = true or d.uploadedById = :userId)
              and (:keywordPattern is null
                    or lower(d.originalFilename) like :keywordPattern escape '!'
                    or lower(u.fullName) like :keywordPattern escape '!')
              and (cast(:uploadedFrom as timestamp) is null or d.uploadedAt >= :uploadedFrom)
              and (cast(:uploadedUntil as timestamp) is null or d.uploadedAt < :uploadedUntil)
            """,
            countQuery = """
            select count(rt.id)
            from ReviewTaskEntity rt
            join ExtractionResultEntity er on er.id = rt.extractionResultId
            join DocumentEntity d on d.id = er.documentId
            left join UserEntity u on u.id = d.uploadedById
            where d.status = org.involens_be.common.CommonStatus.ACTIVE
              and d.currentState = org.involens_be.common.DocumentState.REVIEW_REQUIRED
              and (:status is null or rt.status = :status)
              and (:manager = true or d.uploadedById = :userId)
              and (:keywordPattern is null
                    or lower(d.originalFilename) like :keywordPattern escape '!'
                    or lower(u.fullName) like :keywordPattern escape '!')
              and (cast(:uploadedFrom as timestamp) is null or d.uploadedAt >= :uploadedFrom)
              and (cast(:uploadedUntil as timestamp) is null or d.uploadedAt < :uploadedUntil)
            """)
    Page<ReviewTaskRow> findVisibleTasks(
            @Param("status") ReviewStatus status,
            @Param("manager") boolean manager,
            @Param("userId") Long userId,
            @Param("keywordPattern") String keywordPattern,
            @Param("uploadedFrom") OffsetDateTime uploadedFrom,
            @Param("uploadedUntil") OffsetDateTime uploadedUntil,
            Pageable pageable);

    /** Carries review-task list columns selected across task, extraction, document, and invoice tables. */
    record ReviewTaskRow(
            UUID id,
            UUID documentId,
            String documentFilename,
            String vendorName,
            LocalDateTime createdAt,
            String reason,
            ReviewStatus status,
            Integer priority) {
    }
}
