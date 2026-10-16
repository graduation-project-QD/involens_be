package org.involens_be.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.involens_be.common.CommonStatus;
import org.involens_be.common.DocumentState;
import org.involens_be.dto.response.DocumentStatusResponse;
import org.involens_be.entity.DocumentEntity;
import org.involens_be.repository.DocumentRepository;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Delivers active document transitions after persistence, without affecting committed business operations. */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentStatusNotificationService {
    private final SimpMessagingTemplate messagingTemplate;
    private final WebSocketSessionService sessions;
    private final DocumentRepository documents;
    private final PlatformTransactionManager transactionManager;

    /** Captures a new active document or changed state and defers delivery until its transaction commits. */
    public void afterCommit(DocumentEntity document, DocumentState previousState) {
        log.debug("Preparing document status notification. documentId={}", document.getId());
        if (document.getStatus() != CommonStatus.ACTIVE || previousState == document.getCurrentState()) {
            log.debug("Skipping inactive or unchanged document. documentId={}", document.getId());
            return;
        }
        DocumentStatusChangedEvent event = new DocumentStatusChangedEvent(document.getUploadedById(),
                new DocumentStatusResponse(document.getId(), document.getCurrentState(),
                        document.getErrorCode(), document.getErrorMessage()));
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            log.error("Document status notification requires a persistence transaction. documentId={}", document.getId());
            throw new IllegalStateException("Document status notification requires transaction synchronization");
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            /** Sends the captured transition only when the document transaction has committed successfully. */
            @Override
            public void afterCommit() {
                log.debug("Document status transaction committed. documentId={}", event.payload().documentId());
                notifyCommitted(event);
            }
        });
    }

    /** Sends an already committed snapshot to authorized live sessions, suppressing notification failures. */
    public void notifyCommitted(DocumentStatusChangedEvent event) {
        log.debug("Sending committed document status. documentId={}", event.payload().documentId());
        try {
            TransactionTemplate transaction = new TransactionTemplate(transactionManager);
            transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            transaction.setReadOnly(true);
            Boolean active = transaction.execute(status -> documents.findByIdAndStatus(
                    event.payload().documentId(), CommonStatus.ACTIVE).isPresent());
            if (!Boolean.TRUE.equals(active)) {
                log.debug("Skipping document no longer active. documentId={}", event.payload().documentId());
                return;
            }
            for (String sessionId : sessions.recipients(event.ownerId())) {
                try {
                    messagingTemplate.convertAndSendToUser(sessionId, "/queue/document-status", event.payload());
                } catch (RuntimeException exception) {
                    log.error("Document status delivery failed. documentId={}, sessionId={}",
                            event.payload().documentId(), sessionId, exception);
                }
            }
            log.debug("Document status delivery completed. documentId={}", event.payload().documentId());
        } catch (RuntimeException exception) {
            log.error("Committed document notification failed. documentId={}", event.payload().documentId(), exception);
        }
    }
}
