package org.involens_be.service;

import lombok.extern.slf4j.Slf4j;
import org.involens_be.common.*;
import org.involens_be.entity.*;
import org.involens_be.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Checks grouping, read boundaries, approval counts and owner isolation independently of infrastructure. */
@Slf4j @ExtendWith(MockitoExtension.class)
class NotificationServiceTest {
    @Mock private NotificationRepository notifications;
    @Mock private NotificationItemRepository items;
    @Mock private DocumentRepository documents;
    @Mock private NotificationPushService push;
    private NotificationService service;
    private DocumentEntity document;

    /** Creates a current uploader-owned document and isolated notification persistence collaborators. */
    @BeforeEach void setup() {
        log.info("Preparing grouped notification tests");
        service = new NotificationService(notifications, items, documents, push);
        document = DocumentEntity.builder().id(UUID.randomUUID()).uploadedById(12L)
                .originalFilename("invoice.jpg").status(CommonStatus.ACTIVE).currentState(DocumentState.EXTRACT_SUCCESS).build();
    }

    /** Builds a visible group with a stable ID for count and read-state assertions. */
    private NotificationEntity group(NotificationType type, long count) {
        log.debug("Creating notification group fixture. type={}", type);
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        return NotificationEntity.builder().id(UUID.randomUUID()).recipientId(12L).type(type)
                .visible(true).itemCount(count).createdAt(now).updatedAt(now).build();
    }

    /** Merges new documents under the same unread ID and deduplicates repeated contributions. */
    @Test void mergesUnreadDocumentsWithoutDoubleCounting() {
        log.info("Testing grouped unique-document counts");
        var group = group(NotificationType.SUCCESS, 2);
        when(notifications.findByRecipientIdAndTypeAndReadAtIsNullAndVisibleTrue(12L, NotificationType.SUCCESS))
                .thenReturn(Optional.of(group));
        when(items.existsByNotificationIdAndSourceId(group.getId(), document.getId())).thenReturn(false, true);
        service.recordOutcome(document);
        service.recordOutcome(document);
        assertEquals(3, group.getItemCount());
        verify(items).save(argThat(item -> document.getId().equals(item.getDocumentId())));
        verify(push).afterCommit(12L, group.getId());
        InOrder order = inOrder(notifications);
        order.verify(notifications).lockOwner(12L);
        order.verify(notifications).findByRecipientIdAndTypeAndReadAtIsNullAndVisibleTrue(12L, NotificationType.SUCCESS);
    }

    /** Creates a new unread entry when previously read history is no longer eligible for grouping. */
    @Test void createsNewUnreadGroupAfterReadBoundary() {
        log.info("Testing new group after read boundary");
        when(notifications.findByRecipientIdAndTypeAndReadAtIsNullAndVisibleTrue(12L, NotificationType.SUCCESS))
                .thenReturn(Optional.empty());
        when(notifications.save(any())).thenAnswer(invocation -> {
            NotificationEntity result = invocation.getArgument(0);
            if (result.getId() == null) result.setId(UUID.randomUUID());
            return result;
        });
        service.recordOutcome(document);
        ArgumentCaptor<NotificationEntity> saved = ArgumentCaptor.forClass(NotificationEntity.class);
        verify(notifications, times(2)).save(saved.capture());
        assertEquals(1, saved.getValue().getItemCount());
        assertNull(saved.getValue().getReadAt());
        assertTrue(saved.getValue().isVisible());
    }

    /** Excludes upload/processing activity from the bell, even while dashboard state continues changing. */
    @ParameterizedTest @EnumSource(value = DocumentState.class, names = {"UPLOADED", "PROCESSING"})
    void ignoresIntermediateStates(DocumentState state) {
        log.info("Testing intermediate state suppression. state={}", state);
        document.setCurrentState(state);
        service.recordOutcome(document);
        verifyNoInteractions(notifications, items, push);
    }

    /** Uses the shared failure group for upload validation, dispatch and extraction failures. */
    @ParameterizedTest @EnumSource(value = DocumentState.class, names = {"UPLOADED_FAILED", "PENDING_FAILURE", "EXTRACT_FAILED"})
    void recordsFailureDetails(DocumentState state) {
        log.info("Testing failure outcome. state={}", state);
        document.setCurrentState(state);
        document.setErrorCode("FAILED");
        document.setErrorMessage("Please retry");
        var group = group(NotificationType.FAILURE, 0);
        when(notifications.findByRecipientIdAndTypeAndReadAtIsNullAndVisibleTrue(12L, NotificationType.FAILURE))
                .thenReturn(Optional.of(group));
        service.recordOutcome(document);
        assertEquals(1, group.getItemCount());
        verify(items).save(argThat(item -> "FAILED".equals(item.getErrorCode()) && "Please retry".equals(item.getErrorMessage())));
    }

    /** Removes the final outstanding review even in read history, without announcing self approval. */
    @Test void selfApprovalHidesResolvedReviewWithoutSuccess() {
        log.info("Testing self approval and zero-count hiding");
        var group = group(NotificationType.REVIEW_REQUIRED, 1);
        group.setReadAt(OffsetDateTime.now(ZoneOffset.UTC));
        var item = NotificationItemEntity.builder().active(true).build();
        when(notifications.findContaining(12L, NotificationType.REVIEW_REQUIRED, document.getId())).thenReturn(List.of(group));
        when(items.findByNotificationIdAndDocumentIdAndActiveTrue(group.getId(), document.getId())).thenReturn(Optional.of(item));
        service.recordApproval(document, DocumentState.REVIEW_REQUIRED, 12L);
        assertEquals(0, group.getItemCount());
        assertFalse(group.isVisible());
        assertFalse(item.isActive());
        assertNotNull(group.getReadAt());
        verify(notifications, never()).findByRecipientIdAndTypeAndReadAtIsNullAndVisibleTrue(anyLong(), any());
        verify(push).afterCommit(12L, group.getId());
    }

    /** Another reviewer moves one contribution from pending review to the uploader's success group. */
    @Test void otherReviewerReducesReviewAndAddsSuccess() {
        log.info("Testing another user's approval notification");
        var review = group(NotificationType.REVIEW_REQUIRED, 3);
        var success = group(NotificationType.SUCCESS, 4);
        when(notifications.findContaining(12L, NotificationType.REVIEW_REQUIRED, document.getId())).thenReturn(List.of(review));
        when(items.findByNotificationIdAndDocumentIdAndActiveTrue(review.getId(), document.getId()))
                .thenReturn(Optional.of(NotificationItemEntity.builder().active(true).build()));
        when(notifications.findByRecipientIdAndTypeAndReadAtIsNullAndVisibleTrue(12L, NotificationType.SUCCESS)).thenReturn(Optional.of(success));
        service.recordApproval(document, DocumentState.REVIEW_REQUIRED, 99L);
        assertEquals(2, review.getItemCount());
        assertTrue(review.isVisible());
        assertEquals(5, success.getItemCount());
        verify(push).afterCommit(12L, review.getId());
        verify(push).afterCommit(12L, success.getId());
    }

    /** Editing an already successful invoice cannot manufacture another approval event. */
    @Test void ignoresEditsOfSuccessfulInvoices() {
        log.info("Testing repeat invoice edit suppression");
        service.recordApproval(document, DocumentState.EXTRACT_SUCCESS, 99L);
        verifyNoInteractions(notifications, items, push);
    }

    /** Repeated reads are idempotent and never delete the original visible entry. */
    @Test void markReadPreservesHistoryAndIsIdempotent() {
        log.info("Testing idempotent notification reads");
        var group = group(NotificationType.SUCCESS, 8);
        when(notifications.findByIdAndRecipientIdAndVisibleTrue(group.getId(), 12L)).thenReturn(Optional.of(group));
        service.markRead(12L, group.getId());
        service.markRead(12L, group.getId());
        assertNotNull(group.getReadAt());
        assertTrue(group.isVisible());
        assertEquals(8, group.getItemCount());
        verify(notifications).save(group);
        verify(push).afterCommit(12L, group.getId());
    }

    /** Mark-all-read acquires the same owner lock as grouping and clears all currently unread dots. */
    @Test void markAllReadSerializesWithGroupingAndKeepsHistory() {
        log.info("Testing mark all read boundary");
        var success = group(NotificationType.SUCCESS, 8);
        var review = group(NotificationType.REVIEW_REQUIRED, 3);
        when(notifications.findByRecipientIdAndReadAtIsNullAndVisibleTrue(12L)).thenReturn(List.of(success, review));
        service.markAllRead(12L);
        assertNotNull(success.getReadAt());
        assertEquals(success.getReadAt(), review.getReadAt());
        assertTrue(success.isVisible());
        assertTrue(review.isVisible());
        InOrder order = inOrder(notifications, push);
        order.verify(notifications).lockOwner(12L);
        order.verify(notifications).findByRecipientIdAndReadAtIsNullAndVisibleTrue(12L);
        order.verify(notifications).saveAll(List.of(success, review));
        order.verify(push).afterCommit(12L, null);
    }

    /** Distinguishes the unread group badge from the number of invoices inside those groups. */
    @Test void summarySeparatesBadgeAndFileCounts() {
        log.info("Testing badge versus file counts");
        when(notifications.findByRecipientIdAndReadAtIsNullAndVisibleTrue(12L)).thenReturn(List.of(
                group(NotificationType.SUCCESS, 8), group(NotificationType.REVIEW_REQUIRED, 3), group(NotificationType.FAILURE, 2)));
        var summary = service.summary(12L);
        assertEquals(3, summary.unreadCount());
        assertEquals(8, summary.successCount());
        assertEquals(3, summary.reviewRequiredCount());
        assertEquals(2, summary.failureCount());
    }

    /** Foreign or hidden IDs return the same not-found result for read and related-file operations. */
    @Test void rejectsForeignNotificationAccess() {
        log.info("Testing notification owner isolation");
        UUID id = UUID.randomUUID();
        when(notifications.findByIdAndRecipientIdAndVisibleTrue(id, 12L)).thenReturn(Optional.empty());
        assertEquals(404, assertThrows(ResponseStatusException.class, () -> service.markRead(12L, id)).getStatusCode().value());
        assertEquals(404, assertThrows(ResponseStatusException.class, () -> service.listItems(12L, id, 1, 8)).getStatusCode().value());
        verifyNoInteractions(items, push);
    }

    /** Rejects unbounded or invalid paging before accessing notification history. */
    @Test void rejectsInvalidPagination() {
        log.info("Testing notification pagination validation");
        assertThrows(ResponseStatusException.class, () -> service.list(12L, 0, 8));
        assertThrows(ResponseStatusException.class, () -> service.list(12L, 1, 101));
        assertThrows(ResponseStatusException.class, () -> service.list(12L, 1, 0));
        verifyNoInteractions(notifications);
    }

    /** An already advanced document cannot produce a stale dispatch failure notification. */
    @Test void dispatchNoMatchDoesNotNotify() {
        log.info("Testing conditional dispatch notification");
        assertEquals(0, service.recordDispatch(document, DocumentState.PENDING_FAILURE));
        verifyNoInteractions(notifications, items, push);
    }

    /** A committed dispatch failure includes the standard failure reason and shares the failure group. */
    @Test void dispatchFailureRecordsOutcome() {
        log.info("Testing dispatch failure transaction contents");
        when(documents.updateDispatchStateIfMatching(document.getId(), DocumentState.UPLOADED, DocumentState.PENDING_FAILURE,
                ExtractionMessageService.DISPATCH_FAILED_CODE, ExtractionMessageService.DISPATCH_FAILED_MESSAGE)).thenReturn(1);
        var group = group(NotificationType.FAILURE, 1);
        when(notifications.findByRecipientIdAndTypeAndReadAtIsNullAndVisibleTrue(12L, NotificationType.FAILURE)).thenReturn(Optional.of(group));
        assertEquals(1, service.recordDispatch(document, DocumentState.PENDING_FAILURE));
        assertEquals(2, group.getItemCount());
        verify(items).save(argThat(item -> ExtractionMessageService.DISPATCH_FAILED_CODE.equals(item.getErrorCode())));
    }

    /** A rolled-back upload contributes filenames without inventing nonexistent document IDs. */
    @Test void uploadRollbackRetainsFilesWithoutDocumentLinks() {
        log.info("Testing rolled-back upload notification files");
        var group = group(NotificationType.FAILURE, 0);
        when(notifications.findByRecipientIdAndTypeAndReadAtIsNullAndVisibleTrue(12L, NotificationType.FAILURE)).thenReturn(Optional.of(group));
        service.recordUploadRollback(12L, Map.of(UUID.randomUUID(), "one.jpg", UUID.randomUUID(), "two.jpg"));
        assertEquals(2, group.getItemCount());
        verify(items, times(2)).save(argThat(item -> item.getDocumentId() == null && "UPLOAD_ROLLED_BACK".equals(item.getErrorCode())));
    }
}
