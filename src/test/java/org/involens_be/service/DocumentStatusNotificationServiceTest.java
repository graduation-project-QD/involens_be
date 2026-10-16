package org.involens_be.service;

import lombok.extern.slf4j.Slf4j;
import org.involens_be.common.CommonStatus;
import org.involens_be.common.DocumentState;
import org.involens_be.entity.DocumentEntity;
import org.involens_be.repository.DocumentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Verifies transaction boundaries and best-effort delivery without database or broker connections. */
@Slf4j
class DocumentStatusNotificationServiceTest {
    private SimpMessagingTemplate messaging;
    private WebSocketSessionService sessions;
    private DocumentRepository documents;
    private DocumentStatusNotificationService service;
    private TransactionTemplate transaction;
    private DocumentEntity document;

    /** Prepares real Spring transaction synchronization with isolated persistence and transport collaborators. */
    @BeforeEach
    void setUp() {
        log.info("Preparing document notification tests");
        messaging = mock(SimpMessagingTemplate.class);
        sessions = mock(WebSocketSessionService.class);
        documents = mock(DocumentRepository.class);
        var manager = new TestTransactionManager();
        transaction = new TransactionTemplate(manager);
        service = new DocumentStatusNotificationService(messaging, sessions, documents, manager);
        document = DocumentEntity.builder().id(UUID.randomUUID()).uploadedById(7L)
                .status(CommonStatus.ACTIVE).currentState(DocumentState.PROCESSING).build();
        when(documents.findByIdAndStatus(document.getId(), CommonStatus.ACTIVE)).thenReturn(Optional.of(document));
        when(sessions.recipients(7L)).thenReturn(Set.of("owner-session", "manager-session"));
    }

    /** Ensures delivery happens after commit and uses the captured state rather than a later entity mutation. */
    @Test
    void sendsSnapshotOnlyAfterCommit() {
        log.info("Testing committed document notification snapshot");
        transaction.executeWithoutResult(status -> {
            service.afterCommit(document, DocumentState.UPLOADED);
            document.setCurrentState(DocumentState.EXTRACT_SUCCESS);
            verifyNoInteractions(messaging);
        });
        verify(messaging).convertAndSendToUser(eq("owner-session"), eq("/queue/document-status"),
                argThat((Object payload) -> payload instanceof org.involens_be.dto.response.DocumentStatusResponse response
                        && response.currentState() == DocumentState.PROCESSING
                        && response.documentId().equals(document.getId())));
        verify(messaging).convertAndSendToUser(eq("manager-session"), eq("/queue/document-status"), any(Object.class));
    }

    /** Prevents notifications when persistence is rolled back, including explicitly marked rollback-only work. */
    @Test
    void suppressesRolledBackChanges() {
        log.info("Testing rollback suppression");
        transaction.executeWithoutResult(status -> {
            service.afterCommit(document, DocumentState.UPLOADED);
            status.setRollbackOnly();
        });
        verifyNoInteractions(messaging, sessions);
    }

    /** Avoids notifications for unchanged states and inactive documents. */
    @Test
    void suppressesInactiveAndUnchangedDocuments() {
        log.info("Testing document notification filtering");
        transaction.executeWithoutResult(status -> {
            service.afterCommit(document, DocumentState.PROCESSING);
            document.setStatus(CommonStatus.INACTIVE);
            service.afterCommit(document, DocumentState.UPLOADED);
        });
        verifyNoInteractions(messaging, sessions);
    }

    /** Rechecks active visibility after commit so deactivated documents are not broadcast. */
    @Test
    void suppressesDocumentsDeactivatedBeforeDelivery() {
        log.info("Testing active status recheck");
        when(documents.findByIdAndStatus(document.getId(), CommonStatus.ACTIVE)).thenReturn(Optional.empty());
        transaction.executeWithoutResult(status -> service.afterCommit(document, DocumentState.UPLOADED));
        verifyNoInteractions(messaging, sessions);
    }

    /** Keeps committed work successful and continues other recipients when a WebSocket send fails. */
    @Test
    void isolatesDeliveryFailures() {
        log.info("Testing document delivery failure isolation");
        doThrow(new IllegalStateException("Disconnected"))
                .when(messaging).convertAndSendToUser(eq("owner-session"), anyString(), any(Object.class));
        assertDoesNotThrow(() -> transaction.executeWithoutResult(
                status -> service.afterCommit(document, DocumentState.UPLOADED)));
        verify(messaging).convertAndSendToUser(eq("manager-session"), eq("/queue/document-status"), any(Object.class));
    }

    /** Ensures initial active document creation includes failure details in the same four-field payload. */
    @Test
    void sendsInitialActiveFailure() {
        log.info("Testing failed document creation notification");
        document.setCurrentState(DocumentState.UPLOADED_FAILED);
        document.setErrorCode("UNSUPPORTED_FORMAT");
        document.setErrorMessage("Unsupported file");
        transaction.executeWithoutResult(status -> service.afterCommit(document, null));
        verify(messaging).convertAndSendToUser(eq("owner-session"), anyString(),
                argThat((Object payload) -> payload instanceof org.involens_be.dto.response.DocumentStatusResponse response
                        && "UNSUPPORTED_FORMAT".equals(response.errorCode())
                        && "Unsupported file".equals(response.errorMessage())));
    }

    /** Exercises Spring commit and rollback synchronization without connecting to an external database. */
    private static class TestTransactionManager extends AbstractPlatformTransactionManager {
        /** Supplies a distinct transaction resource for each isolated test operation. */
        @Override
        protected Object doGetTransaction() {
            log.debug("Creating test transaction resource");
            return new Object();
        }

        /** Starts the test transaction while Spring manages its synchronization callbacks. */
        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            log.debug("Beginning test transaction");
        }

        /** Marks test persistence committed before Spring invokes notification callbacks. */
        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            log.debug("Committing test transaction");
        }

        /** Marks failed test persistence rolled back without executing after-commit callbacks. */
        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            log.debug("Rolling back test transaction");
        }
    }
}
