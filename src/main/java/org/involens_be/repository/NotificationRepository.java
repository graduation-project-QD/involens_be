package org.involens_be.repository;

import org.involens_be.common.NotificationType;
import org.involens_be.entity.NotificationEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.*;

public interface NotificationRepository extends JpaRepository<NotificationEntity, UUID> {
    /** Serializes grouping and read operations for an uploader, including when no group exists yet. */
    @Query(value = "select 1 from pg_advisory_xact_lock(hashtextextended('notification:' || cast(:owner as text), 0))", nativeQuery = true)
    Integer lockOwner(@Param("owner") Long owner);

    /** Finds the current unread visible group into which an uploader's outcome can be merged. */
    Optional<NotificationEntity> findByRecipientIdAndTypeAndReadAtIsNullAndVisibleTrue(Long owner, NotificationType type);

    /** Lists the uploader's visible history, including read entries. */
    Page<NotificationEntity> findByRecipientIdAndVisibleTrue(Long owner, Pageable page);

    /** Resolves a visible bell entry without exposing another uploader's data. */
    Optional<NotificationEntity> findByIdAndRecipientIdAndVisibleTrue(UUID id, Long owner);

    /** Finds unread groups for the badge and the mark-all-read operation. */
    List<NotificationEntity> findByRecipientIdAndReadAtIsNullAndVisibleTrue(Long owner);

    /** Finds review groups still containing a document, even if already read. */
    @Query("select n from NotificationEntity n where n.recipientId = :owner and n.type = :type "
            + "and n.visible = true and n.id in (select i.notificationId from NotificationItemEntity i "
            + "where i.documentId = :document and i.active = true)")
    List<NotificationEntity> findContaining(@Param("owner") Long owner, @Param("type") NotificationType type,
                                           @Param("document") UUID document);
}
