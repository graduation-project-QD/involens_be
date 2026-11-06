package org.involens_be.service;

import lombok.extern.slf4j.Slf4j;
import org.involens_be.dto.response.NotificationResponse;
import org.involens_be.entity.NotificationEntity;
import org.involens_be.repository.NotificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.*;
import java.util.Set;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Verifies durable notification changes are announced only after a successful transaction. */
@Slf4j
class NotificationPushServiceTest {
    private WebSocketSessionService sessions;
    private SimpMessagingTemplate messaging;
    private NotificationRepository notifications;
    private NotificationPushService service;
    private TransactionTemplate transaction;

    /** Creates real Spring synchronization around mocked WebSocket transports. */
    @BeforeEach void setup() {
        log.info("Preparing bell delivery transaction tests");
        sessions = mock(WebSocketSessionService.class);
        messaging = mock(SimpMessagingTemplate.class);
        notifications = mock(NotificationRepository.class);
        TestTransactionManager transactionManager = new TestTransactionManager();
        service = new NotificationPushService(sessions, messaging, notifications, transactionManager);
        transaction = new TransactionTemplate(transactionManager);
    }

    /** Several changes in one transaction cause one refresh per uploader tab, after commit only. */
    @Test void coalescesCommittedChanges() {
        log.info("Testing one committed refresh per uploader transaction");
        when(sessions.ownerRecipients(12L)).thenReturn(Set.of("tab-one", "tab-two"));
        when(notifications.findByRecipientIdAndReadAtIsNullAndVisibleTrue(12L))
                .thenReturn(List.of(NotificationEntity.builder().itemCount(5).build()));
        transaction.executeWithoutResult(status -> {
            service.afterCommit(12L, UUID.randomUUID());
            service.afterCommit(12L, UUID.randomUUID());
            verifyNoInteractions(messaging, sessions, notifications);
        });
        verify(sessions).ownerRecipients(12L);
        verify(notifications).findByRecipientIdAndReadAtIsNullAndVisibleTrue(12L);
        verify(messaging).convertAndSendToUser("tab-one", "/queue/notifications", new NotificationResponse.Change("NOTIFICATIONS_CHANGED", null, 1));
        verify(messaging).convertAndSendToUser("tab-two", "/queue/notifications", new NotificationResponse.Change("NOTIFICATIONS_CHANGED", null, 1));
        verifyNoMoreInteractions(messaging);
    }

    /** Rolled-back persistence cannot leave a phantom unread bell update. */
    @Test void suppressesRollback() {
        log.info("Testing rollback suppression for bell delivery");
        transaction.executeWithoutResult(status -> {
            service.afterCommit(12L, UUID.randomUUID());
            status.setRollbackOnly();
        });
        verifyNoInteractions(sessions, messaging, notifications);
    }

    /** Clears the badge when no visible unread groups remain, preserving the changed entry ID. */
    @Test void sendsZeroUnreadCount() {
        log.info("Testing empty unread badge delivery");
        UUID notificationId = UUID.randomUUID();
        when(sessions.ownerRecipients(12L)).thenReturn(Set.of("tab"));
        when(notifications.findByRecipientIdAndReadAtIsNullAndVisibleTrue(12L)).thenReturn(List.of());
        transaction.executeWithoutResult(status -> service.afterCommit(12L, notificationId));
        verify(messaging).convertAndSendToUser("tab", "/queue/notifications",
                new NotificationResponse.Change("NOTIFICATIONS_CHANGED", notificationId, 0));
    }

    /** A count lookup failure cannot fail committed work or send an incorrect zero badge. */
    @Test void containsUnreadLookupFailure() {
        log.info("Testing unread lookup failure containment");
        when(notifications.findByRecipientIdAndReadAtIsNullAndVisibleTrue(12L))
                .thenThrow(new IllegalStateException("database unavailable"));
        assertDoesNotThrow(() -> transaction.executeWithoutResult(status -> service.afterCommit(12L, UUID.randomUUID())));
        verifyNoInteractions(messaging);
    }

    /** A broken transport cannot turn a committed invoice operation into an API failure. */
    @Test void containsTransportFailure() {
        log.info("Testing bell transport failure containment");
        when(sessions.ownerRecipients(12L)).thenReturn(Set.of("tab"));
        doThrow(new IllegalStateException("transport unavailable")).when(messaging)
                .convertAndSendToUser(eq("tab"), eq("/queue/notifications"), any(Object.class));
        assertDoesNotThrow(() -> transaction.executeWithoutResult(status -> service.afterCommit(12L, UUID.randomUUID())));
    }

    /** Supplies transaction synchronization without any database or external side effects. */
    private static class TestTransactionManager extends AbstractPlatformTransactionManager {
        /** Allocates an isolated synchronization transaction for this test. */
        @Override protected Object doGetTransaction() {
            log.debug("Creating bell test transaction");
            return new Object();
        }
        /** Begins the test transaction managed by Spring's synchronization lifecycle. */
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) {
            log.debug("Beginning bell test transaction");
        }
        /** Completes persistence before the framework invokes after-commit delivery. */
        @Override protected void doCommit(DefaultTransactionStatus status) {
            log.debug("Committing bell test transaction");
        }
        /** Rolls back without invoking after-commit delivery. */
        @Override protected void doRollback(DefaultTransactionStatus status) {
            log.debug("Rolling back bell test transaction");
        }
    }
}
