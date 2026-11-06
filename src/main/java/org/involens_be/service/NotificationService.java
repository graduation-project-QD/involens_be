package org.involens_be.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.involens_be.common.*;
import org.involens_be.dto.response.NotificationResponse;
import org.involens_be.entity.*;
import org.involens_be.repository.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;

/** Maintains durable uploader-only notifications, merging only visible unread entries. */
@Service @RequiredArgsConstructor @Slf4j
public class NotificationService {
    private final NotificationRepository notifications;
    private final NotificationItemRepository items;
    private final DocumentRepository documents;
    private final NotificationPushService push;

    /** Records an upload or AI outcome in the caller's transaction, excluding intermediate states. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordOutcome(DocumentEntity document) {
        log.debug("Recording document bell outcome. documentId={}, state={}", document.getId(), document.getCurrentState());
        if (document.getStatus() != CommonStatus.ACTIVE) {
            log.debug("Skipping inactive document bell outcome. documentId={}", document.getId());
            return;
        }
        NotificationType type = switch (document.getCurrentState()) {
            case EXTRACT_SUCCESS -> NotificationType.SUCCESS;
            case REVIEW_REQUIRED -> NotificationType.REVIEW_REQUIRED;
            case UPLOADED_FAILED, EXTRACT_FAILED, PENDING_FAILURE -> NotificationType.FAILURE;
            default -> null;
        };
        if (type == null) {
            log.debug("Skipping intermediate document state. documentId={}", document.getId());
            return;
        }
        notifications.lockOwner(document.getUploadedById());
        add(document.getUploadedById(), type, document.getId(), document.getId(),
                document.getOriginalFilename(), document.getErrorCode(), document.getErrorMessage());
        log.info("Document bell outcome recorded. documentId={}, type={}", document.getId(), type);
    }

    /** Removes outstanding review contributions and informs the uploader only when someone else approved. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordApproval(DocumentEntity document, DocumentState previousState, Long reviewerId) {
        log.info("Recording approval notification. documentId={}, reviewerId={}", document.getId(), reviewerId);
        if (document.getStatus() != CommonStatus.ACTIVE || previousState == DocumentState.EXTRACT_SUCCESS) {
            log.debug("Skipping inactive document or edit of an already successful invoice. documentId={}", document.getId());
            return;
        }
        Long owner = document.getUploadedById();
        notifications.lockOwner(owner);
        for (NotificationEntity notification : notifications.findContaining(owner, NotificationType.REVIEW_REQUIRED, document.getId())) {
            items.findByNotificationIdAndDocumentIdAndActiveTrue(notification.getId(), document.getId()).ifPresent(item -> {
                item.setActive(false);
                items.save(item);
                notification.setItemCount(notification.getItemCount() - 1);
                notification.setVisible(notification.getItemCount() > 0);
                notification.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
                notifications.save(notification);
                push.afterCommit(owner, notification.getId());
                log.info("Reduced pending review notification. notificationId={}, count={}", notification.getId(), notification.getItemCount());
            });
        }
        if (!Objects.equals(owner, reviewerId)) {
            add(owner, NotificationType.SUCCESS, document.getId(), document.getId(), document.getOriginalFilename(), null, null);
        } else {
            log.info("Suppressing self-approval success notification. documentId={}", document.getId());
        }
        log.info("Approval notification handling completed. documentId={}", document.getId());
    }

    /** Commits dispatch state and its failure notification together after the upload transaction has completed. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int recordDispatch(DocumentEntity document, DocumentState state) {
        log.info("Persisting dispatch and bell outcome. documentId={}, state={}", document.getId(), state);
        String errorCode = state == DocumentState.PENDING_FAILURE ? ExtractionMessageService.DISPATCH_FAILED_CODE : null;
        String errorMessage = state == DocumentState.PENDING_FAILURE ? ExtractionMessageService.DISPATCH_FAILED_MESSAGE : null;
        int updated = documents.updateDispatchStateIfMatching(document.getId(), DocumentState.UPLOADED, state, errorCode, errorMessage);
        if (updated > 0 && state == DocumentState.PENDING_FAILURE) {
            notifications.lockOwner(document.getUploadedById());
            add(document.getUploadedById(), NotificationType.FAILURE, document.getId(), document.getId(),
                    document.getOriginalFilename(), errorCode, errorMessage);
        }
        log.info("Dispatch and bell outcome persisted. documentId={}, updated={}", document.getId(), updated);
        return updated;
    }

    /** Records each failed file after an upload rollback, when no persisted document is available. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordUploadRollback(Long owner, Map<UUID, String> files) {
        log.info("Recording rolled-back upload notification. owner={}, count={}", owner, files.size());
        notifications.lockOwner(owner);
        files.forEach((source, filename) -> add(owner, NotificationType.FAILURE, source, null, filename,
                "UPLOAD_ROLLED_BACK", "Lần tải lên thất bại. Vui lòng tải lại tài liệu."));
        log.info("Rolled-back upload notification saved. owner={}", owner);
    }

    /** Adds one unique file to an unread group while the uploader's transaction lock is held. */
    private void add(Long owner, NotificationType type, UUID sourceId, UUID documentId,
                     String filename, String errorCode, String errorMessage) {
        log.debug("Merging notification contribution. owner={}, type={}, sourceId={}", owner, type, sourceId);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        NotificationEntity notification = notifications.findByRecipientIdAndTypeAndReadAtIsNullAndVisibleTrue(owner, type)
                .orElseGet(() -> notifications.save(NotificationEntity.builder().recipientId(owner).type(type)
                        .visible(true).createdAt(now).updatedAt(now).build()));
        if (items.existsByNotificationIdAndSourceId(notification.getId(), sourceId)) {
            log.debug("Skipping duplicate notification contribution. notificationId={}, sourceId={}", notification.getId(), sourceId);
            return;
        }
        items.save(NotificationItemEntity.builder().notificationId(notification.getId()).sourceId(sourceId)
                .documentId(documentId).filename(filename).errorCode(errorCode).errorMessage(errorMessage)
                .active(true).createdAt(now).build());
        notification.setItemCount(notification.getItemCount() + 1);
        notification.setUpdatedAt(now);
        notifications.save(notification);
        push.afterCommit(owner, notification.getId());
        log.debug("Notification contribution merged. notificationId={}, count={}", notification.getId(), notification.getItemCount());
    }

    /** Returns visible bell history, newest activity first, scoped strictly to the JWT owner. */
    @Transactional(readOnly = true)
    public NotificationResponse.Listing list(Long owner, int page, int pageSize) {
        log.debug("Loading notification history. owner={}, page={}", owner, page);
        var result = notifications.findByRecipientIdAndVisibleTrue(owner, pagination(page, pageSize));
        return new NotificationResponse.Listing(result.map(this::response).getContent(), result.getTotalElements(), page, pageSize);
    }

    /** Returns unread group count for the red badge and unread file totals for each outcome. */
    @Transactional(readOnly = true)
    public NotificationResponse.Summary summary(Long owner) {
        log.debug("Loading unread notification counts. owner={}", owner);
        var unread = notifications.findByRecipientIdAndReadAtIsNullAndVisibleTrue(owner);
        Map<NotificationType, Long> counts = new EnumMap<>(NotificationType.class);
        unread.forEach(notification -> counts.merge(notification.getType(), notification.getItemCount(), Long::sum));
        return new NotificationResponse.Summary(unread.size(), counts.getOrDefault(NotificationType.SUCCESS, 0L),
                counts.getOrDefault(NotificationType.REVIEW_REQUIRED, 0L), counts.getOrDefault(NotificationType.FAILURE, 0L));
    }

    /** Lists the files still represented by a visible notification belonging to the caller. */
    @Transactional(readOnly = true)
    public NotificationResponse.ItemListing listItems(Long owner, UUID notificationId, int page, int pageSize) {
        log.debug("Loading notification files. owner={}, notificationId={}", owner, notificationId);
        owned(owner, notificationId);
        var result = items.findByNotificationIdAndActiveTrue(notificationId,
                pagination(page, pageSize).withSort(Sort.by(Sort.Direction.DESC, "createdAt", "id")));
        var entries = result.map(item -> new NotificationResponse.Item(item.getId(), item.getDocumentId(),
                item.getFilename(), item.getErrorCode(), item.getErrorMessage())).getContent();
        return new NotificationResponse.ItemListing(entries, result.getTotalElements(), page, pageSize);
    }

    /** Clears one unread dot without removing history, serialized against incoming outcomes. */
    @Transactional
    public void markRead(Long owner, UUID notificationId) {
        log.info("Marking notification read. owner={}, notificationId={}", owner, notificationId);
        notifications.lockOwner(owner);
        NotificationEntity notification = owned(owner, notificationId);
        if (notification.getReadAt() == null) {
            notification.setReadAt(OffsetDateTime.now(ZoneOffset.UTC));
            notifications.save(notification);
            push.afterCommit(owner, notificationId);
        }
        log.info("Notification marked read. notificationId={}", notificationId);
    }

    /** Clears all current unread dots atomically; events arriving after this operation remain unread. */
    @Transactional
    public void markAllRead(Long owner) {
        log.info("Marking all notifications read. owner={}", owner);
        notifications.lockOwner(owner);
        var unread = notifications.findByRecipientIdAndReadAtIsNullAndVisibleTrue(owner);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        unread.forEach(notification -> notification.setReadAt(now));
        notifications.saveAll(unread);
        if (!unread.isEmpty()) {
            push.afterCommit(owner, null);
        }
        log.info("All current notifications marked read. owner={}, count={}", owner, unread.size());
    }

    /** Rejects absent, hidden or foreign notification IDs without revealing their ownership. */
    private NotificationEntity owned(Long owner, UUID id) {
        log.debug("Checking notification ownership. owner={}, notificationId={}", owner, id);
        return notifications.findByIdAndRecipientIdAndVisibleTrue(id, owner).orElseThrow(() -> {
            log.warn("Notification is not visible to caller. owner={}, notificationId={}", owner, id);
            return new ResponseStatusException(HttpStatus.NOT_FOUND, "Notification not found");
        });
    }

    /** Validates pagination using the existing document API's one-based page and size limits. */
    private PageRequest pagination(int page, int pageSize) {
        log.debug("Validating notification pagination. page={}, pageSize={}", page, pageSize);
        if (page < 1 || pageSize < 1 || pageSize > 100) {
            log.warn("Invalid notification pagination. page={}, pageSize={}", page, pageSize);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "page >= 1 and page_size between 1 and 100 required");
        }
        return PageRequest.of(page - 1, pageSize, Sort.by(Sort.Direction.DESC, "updatedAt", "id"));
    }

    /** Maps a persisted group to the bell contract with an explicit read flag and file count. */
    private NotificationResponse response(NotificationEntity notification) {
        log.debug("Mapping notification response. notificationId={}", notification.getId());
        return new NotificationResponse(notification.getId(), notification.getType(), notification.getItemCount(),
                notification.getReadAt() != null, notification.getReadAt(), notification.getCreatedAt(), notification.getUpdatedAt());
    }
}
