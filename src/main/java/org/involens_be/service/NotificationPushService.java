package org.involens_be.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.involens_be.dto.response.NotificationResponse;
import org.involens_be.repository.NotificationRepository;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.UUID;

/** Publishes unread group counts and bell refresh hints after notification changes commit. */
@Service @RequiredArgsConstructor @Slf4j
public class NotificationPushService {
    private final WebSocketSessionService sessions;
    private final SimpMessagingTemplate messaging;
    private final NotificationRepository notifications;
    private final PlatformTransactionManager transactionManager;

    /** Schedules a bell refresh only if the notification transaction commits. */
    public void afterCommit(Long owner, UUID notificationId) {
        log.debug("Scheduling bell refresh. owner={}, notificationId={}", owner, notificationId);
        for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
            if (synchronization instanceof BellRefresh refresh && refresh.owner.equals(owner)) {
                refresh.notificationId = java.util.Objects.equals(refresh.notificationId, notificationId) ? notificationId : null;
                log.debug("Coalesced bell refresh for transaction. owner={}", owner);
                return;
            }
        }
        TransactionSynchronizationManager.registerSynchronization(new BellRefresh(owner, notificationId));
    }

    /** Holds at most one refresh callback per recipient and transaction, including multi-file uploads. */
    private class BellRefresh implements TransactionSynchronization {
        private final Long owner;
        private UUID notificationId;

        /** Captures the recipient and optional single changed entry for delivery after commit. */
        private BellRefresh(Long owner, UUID notificationId) {
            log.debug("Capturing transaction bell refresh. owner={}", owner);
            this.owner = owner;
            this.notificationId = notificationId;
        }

        /** Notifies authenticated uploader tabs only after the notification transaction succeeds. */
        @Override public void afterCommit() {
            log.debug("Publishing committed bell refresh. owner={}", owner);
            send(owner, notificationId);
        }
    }

    /** Reads committed unread groups and sends their count to uploader tabs, containing delivery failures. */
    private void send(Long owner, UUID notificationId) {
        log.debug("Sending uploader bell refresh. owner={}", owner);
        try {
            TransactionTemplate transaction = new TransactionTemplate(transactionManager);
            transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            transaction.setReadOnly(true);
            long unreadCount = transaction.execute(status ->
                    (long) notifications.findByRecipientIdAndReadAtIsNullAndVisibleTrue(owner).size());
            NotificationResponse.Change change = new NotificationResponse.Change(
                    "NOTIFICATIONS_CHANGED", notificationId, unreadCount);
            for (String session : sessions.ownerRecipients(owner)) {
                try {
                    messaging.convertAndSendToUser(session, "/queue/notifications",
                            change);
                } catch (RuntimeException exception) {
                    log.error("Bell refresh delivery failed. owner={}, session={}", owner, session, exception);
                }
            }
            log.debug("Bell refresh delivery completed. owner={}, unreadCount={}", owner, unreadCount);
        } catch (RuntimeException exception) {
            log.error("Bell refresh preparation failed. owner={}", owner, exception);
        }
    }
}
