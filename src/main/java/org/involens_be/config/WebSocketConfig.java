package org.involens_be.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.involens_be.service.WebSocketSessionService;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;

/** Configures the single-instance private document notification transport. */
@Slf4j
@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {
    private final WebSocketSecurityInterceptor security;
    private final WebSocketSessionService sessions;

    /** Exposes the native STOMP endpoint only to the approved frontend origins. */
    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        log.info("Configuring document WebSocket endpoint /ws");
        registry.addEndpoint("/ws").setAllowedOrigins("http://localhost:5173", "http://3.25.226.163");
        registry.setPreserveReceiveOrder(true);
    }

    /** Enables private queues in memory and preserves notification order within each connection. */
    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        log.info("Configuring document status in-memory broker");
        registry.enableSimpleBroker("/queue");
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
        registry.setPreservePublishOrder(true);
    }

    /** Installs token and subscription validation before incoming frames reach the broker. */
    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        log.info("Configuring document WebSocket authorization");
        registration.interceptors(security);
    }

    /** Tracks transport lifecycle so token expiry closes the socket and disconnects release timers. */
    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        log.info("Configuring document WebSocket connection lifecycle");
        registration.addDecoratorFactory(handler -> new WebSocketHandlerDecorator(handler) {
            /** Registers the opened transport before accepting its STOMP authentication. */
            @Override
            public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                log.debug("Document WebSocket connected. sessionId={}", session.getId());
                sessions.opened(session);
                try {
                    super.afterConnectionEstablished(session);
                } catch (Exception exception) {
                    sessions.closed(session.getId());
                    throw exception;
                }
            }

            /** Removes the closed connection from authorized document notification recipients. */
            @Override
            public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
                log.debug("Document WebSocket disconnected. sessionId={}", session.getId());
                sessions.closed(session.getId());
                super.afterConnectionClosed(session, status);
            }
        });
    }
}
