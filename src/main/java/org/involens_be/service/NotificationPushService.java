package org.involens_be.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.involens_be.dto.response.NotificationResponse;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.UUID;

/** Invalidates uploader bell data after commit so clients can reload an authoritative snapshot. */
@Service @RequiredArgsConstructor @Slf4j
public class NotificationPushService {
    private final WebSocketSessionService sessions;
    private final SimpMessagingTemplate messaging;

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

    /** Sends a refresh hint without rolling back committed business operations on transport failure. */
    private void send(Long owner, UUID notificationId) {
        log.debug("Sending uploader bell refresh. owner={}", owner);
        try {
            for (String session : sessions.ownerRecipients(owner)) {
                try {
                    messaging.convertAndSendToUser(session, "/queue/notifications",
                            new NotificationResponse.Change("NOTIFICATIONS_CHANGED", notificationId));
                } catch (RuntimeException exception) {
                    log.error("Bell refresh delivery failed. owner={}, session={}", owner, session, exception);
                }
            }
            log.debug("Bell refresh delivery completed. owner={}", owner);
        } catch (RuntimeException exception) {
            log.error("Bell recipient lookup failed. owner={}", owner, exception);
        }
    }
}
