package org.involens_be.service;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.involens_be.common.UserRole;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Tracks local connections and closes expired tokens while enforcing current account visibility. */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebSocketSessionService {
    private final JwtDecoder decoder;
    private final Map<String, WebSocketSession> sockets = new ConcurrentHashMap<>();
    private final Map<String, Jwt> tokens = new ConcurrentHashMap<>();
    private final Map<String, ScheduledFuture<?>> expiryTasks = new ConcurrentHashMap<>();
    private final ScheduledThreadPoolExecutor expirationExecutor = new ScheduledThreadPoolExecutor(1);

    /** Registers the transport so authenticated token expiry can close its underlying connection. */
    public void opened(WebSocketSession session) {
        log.debug("Registering document WebSocket transport. sessionId={}", session.getId());
        expirationExecutor.setRemoveOnCancelPolicy(true);
        sockets.put(session.getId(), session);
    }

    /** Binds one validated token to the transport and schedules closure at its exact expiry time. */
    public void authenticate(String sessionId, Jwt token) {
        log.debug("Authenticating document WebSocket session. sessionId={}", sessionId);
        if (sessionId == null || !sockets.containsKey(sessionId) || token.getExpiresAt() == null
                || !token.getExpiresAt().isAfter(Instant.now()) || tokens.putIfAbsent(sessionId, token) != null) {
            log.warn("Document WebSocket authentication rejected. sessionId={}", sessionId);
            throw new AccessDeniedException("Invalid WebSocket session");
        }
        ScheduledFuture<?> task = expirationExecutor.schedule(() -> close(sessionId),
                Math.max(0, Duration.between(Instant.now(), token.getExpiresAt()).toMillis()), TimeUnit.MILLISECONDS);
        expiryTasks.put(sessionId, task);
        if (!sockets.containsKey(sessionId)) {
            closed(sessionId);
        }
        log.info("Document WebSocket authenticated. sessionId={}", sessionId);
    }

    /** Revalidates signature, expiry and account status before a frame or notification uses this session. */
    public Jwt validatedToken(String sessionId) {
        log.debug("Checking document WebSocket authorization. sessionId={}", sessionId);
        Jwt token = sessionId == null ? null : tokens.get(sessionId);
        if (token == null || !token.getExpiresAt().isAfter(Instant.now())) {
            log.warn("Document WebSocket session is unauthenticated or expired. sessionId={}", sessionId);
            throw new AccessDeniedException("WebSocket authentication required");
        }
        return decoder.decode(token.getTokenValue());
    }

    /** Returns only sessions belonging to the uploader or a currently authorized manager. */
    public Set<String> recipients(Long ownerId) {
        log.debug("Selecting document status recipients. ownerId={}", ownerId);
        Set<String> recipients = new HashSet<>();
        for (String sessionId : tokens.keySet()) {
            try {
                Jwt token = validatedToken(sessionId);
                Number userId = token.getClaim("userId");
                if (UserRole.MANAGER.name().equals(token.getClaimAsString("role"))
                        || (userId != null && Long.valueOf(userId.longValue()).equals(ownerId))) {
                    recipients.add(sessionId);
                }
            } catch (RuntimeException exception) {
                log.warn("Closing document WebSocket with invalid authorization. sessionId={}", sessionId);
                close(sessionId);
            }
        }
        return recipients;
    }

    /** Releases token and timer state when the underlying connection ends. */
    public void closed(String sessionId) {
        log.debug("Removing document WebSocket session. sessionId={}", sessionId);
        sockets.remove(sessionId);
        tokens.remove(sessionId);
        ScheduledFuture<?> task = expiryTasks.remove(sessionId);
        if (task != null) {
            task.cancel(false);
        }
    }

    /** Closes an expired or revoked connection and removes it from notification recipients. */
    private void close(String sessionId) {
        log.info("Closing document WebSocket authorization. sessionId={}", sessionId);
        WebSocketSession socket = sockets.get(sessionId);
        closed(sessionId);
        if (socket != null && socket.isOpen()) {
            try {
                socket.close(CloseStatus.POLICY_VIOLATION);
            } catch (IOException exception) {
                log.warn("Document WebSocket close failed. sessionId={}", sessionId, exception);
            }
        }
    }

    /** Stops expiry timers and closes remaining document subscriptions on application shutdown. */
    @PreDestroy
    public void shutdown() {
        log.info("Stopping document WebSocket sessions");
        expirationExecutor.shutdownNow();
        Set.copyOf(sockets.keySet()).forEach(this::close);
    }
}
