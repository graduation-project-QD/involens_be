package org.involens_be.service;

import lombok.extern.slf4j.Slf4j;
import org.involens_be.dto.response.NotificationResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.*;
import java.util.Set;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Verifies durable notification changes are announced only after a successful transaction. */
@Slf4j
class NotificationPushServiceTest {
    private WebSocketSessionService sessions;
    private SimpMessagingTemplate messaging;
    private NotificationPushService service;
    private TransactionTemplate transaction;

    /** Creates real Spring synchronization around mocked WebSocket transports. */
    @BeforeEach void setup() {
        log.info("Preparing bell delivery transaction tests");
        sessions = mock(WebSocketSessionService.class);
        messaging = mock(SimpMessagingTemplate.class);
        service = new NotificationPushService(sessions, messaging);
        transaction = new TransactionTemplate(new TestTransactionManager());
    }

    /** Several changes in one transaction cause one refresh per uploader tab, after commit only. */
    @Test void coalescesCommittedChanges() {
        log.info("Testing one committed refresh per uploader transaction");
        when(sessions.ownerRecipients(12L)).thenReturn(Set.of("tab-one", "tab-two"));
        transaction.executeWithoutResult(status -> {
            service.afterCommit(12L, UUID.randomUUID());
            service.afterCommit(12L, UUID.randomUUID());
            verifyNoInteractions(messaging, sessions);
        });
        verify(sessions).ownerRecipients(12L);
        verify(messaging).convertAndSendToUser("tab-one", "/queue/notifications", new NotificationResponse.Change("NOTIFICATIONS_CHANGED", null));
        verify(messaging).convertAndSendToUser("tab-two", "/queue/notifications", new NotificationResponse.Change("NOTIFICATIONS_CHANGED", null));
        verifyNoMoreInteractions(messaging);
    }

    /** Rolled-back persistence cannot leave a phantom unread bell update. */
    @Test void suppressesRollback() {
        log.info("Testing rollback suppression for bell delivery");
        transaction.executeWithoutResult(status -> {
            service.afterCommit(12L, UUID.randomUUID());
            status.setRollbackOnly();
        });
        verifyNoInteractions(sessions, messaging);
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
