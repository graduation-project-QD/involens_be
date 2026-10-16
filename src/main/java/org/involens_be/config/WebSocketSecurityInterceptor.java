package org.involens_be.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.involens_be.common.UserRole;
import org.involens_be.service.WebSocketSessionService;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.List;

/** Authenticates STOMP connections and limits clients to their own read-only status subscription. */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebSocketSecurityInterceptor implements ChannelInterceptor {
    private final JwtDecoder decoder;
    private final WebSocketSessionService sessions;

    /** Validates connection tokens and rejects publication or subscriptions outside the private status queue. */
    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null) {
            log.warn("Rejecting document WebSocket frame without STOMP headers");
            throw new AccessDeniedException("STOMP headers required");
        }
        StompCommand command = accessor.getCommand();
        log.debug("Authorizing document WebSocket frame. command={}, sessionId={}", command, accessor.getSessionId());
        try {
            if (command == StompCommand.CONNECT) {
                String authorization = accessor.getFirstNativeHeader("Authorization");
                accessor.removeNativeHeader("Authorization");
                if (authorization == null || !authorization.startsWith("Bearer ")) {
                    throw new AccessDeniedException("Bearer token required");
                }
                Jwt jwt = decoder.decode(authorization.substring(7));
                if (!(jwt.getClaim("userId") instanceof Number)) {
                    throw new AccessDeniedException("User identity required");
                }
                UserRole.valueOf(jwt.getClaimAsString("role"));
                sessions.authenticate(accessor.getSessionId(), jwt);
                // Each authenticated transport gets its own private destination, including multiple browser tabs.
                accessor.setUser(new JwtAuthenticationToken(jwt, List.of(), accessor.getSessionId()));
            } else if (command != StompCommand.DISCONNECT) {
                sessions.validatedToken(accessor.getSessionId());
                if (command == StompCommand.SUBSCRIBE) {
                    if (!"/user/queue/document-status".equals(accessor.getDestination())) {
                        throw new AccessDeniedException("Subscription destination is not permitted");
                    }
                } else if (command != StompCommand.UNSUBSCRIBE && command != null) {
                    throw new AccessDeniedException("Only status subscriptions are permitted");
                }
            }
            return message;
        } catch (RuntimeException exception) {
            log.warn("Document WebSocket frame rejected. command={}, sessionId={}", command, accessor.getSessionId());
            throw new AccessDeniedException("Document WebSocket access denied", exception);
        }
    }
}
