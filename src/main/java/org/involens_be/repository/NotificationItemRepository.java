package org.involens_be.repository;

import org.involens_be.entity.NotificationItemEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;

public interface NotificationItemRepository extends JpaRepository<NotificationItemEntity, UUID> {
    /** Prevents counting the same document twice in an unread group. */
    boolean existsByNotificationIdAndSourceId(UUID notificationId, UUID sourceId);

    /** Locates the outstanding review contribution when its document is approved. */
    Optional<NotificationItemEntity> findByNotificationIdAndDocumentIdAndActiveTrue(UUID notificationId, UUID documentId);

    /** Returns the files represented by a notification without loading an unbounded group. */
    Page<NotificationItemEntity> findByNotificationIdAndActiveTrue(UUID notificationId, Pageable page);
}
