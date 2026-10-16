package org.involens_be.config;

import lombok.extern.slf4j.Slf4j;
import org.involens_be.service.WebSocketSessionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.time.Instant;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Verifies private subscriptions, account visibility and token-expiry transport cleanup. */
@Slf4j
class WebSocketSecurityInterceptorTest {
    private JwtDecoder decoder;
    private WebSocketSessionService sessions;
    private WebSocketSecurityInterceptor security;

    /** Creates the real authorization and session services around an isolated JWT decoder. */
    @BeforeEach
    void setUp() {
        log.info("Preparing document WebSocket security tests");
        decoder = mock(JwtDecoder.class);
        sessions = new WebSocketSessionService(decoder);
        security = new WebSocketSecurityInterceptor(decoder, sessions);
    }

    /** Stops session expiry timers after each authorization test. */
    @AfterEach
    void tearDown() {
        log.info("Cleaning up document WebSocket security test");
        sessions.shutdown();
    }

    /** Produces one signed-token fixture with the same identity claims as login. */
    private Jwt token(String value, long userId, String role, Instant expiresAt) {
        log.debug("Creating document WebSocket token fixture");
        Jwt jwt = Jwt.withTokenValue(value).header("alg", "HS256").subject("user@example.com")
                .claim("userId", userId).claim("role", role).expiresAt(expiresAt).build();
        when(decoder.decode(value)).thenReturn(jwt);
        return jwt;
    }

    /** Opens and authenticates a test transport exactly once through the inbound interceptor. */
    private WebSocketSession connect(String id, Jwt jwt) {
        log.debug("Connecting test document WebSocket. sessionId={}", id);
        WebSocketSession socket = mock(WebSocketSession.class);
        when(socket.getId()).thenReturn(id);
        when(socket.isOpen()).thenReturn(true);
        sessions.opened(socket);
        var accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setSessionId(id);
        accessor.setNativeHeader("Authorization", "Bearer " + jwt.getTokenValue());
        accessor.setLeaveMutable(true);
        security.preSend(MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders()), null);
        assertEquals(id, accessor.getUser().getName());
        assertNull(accessor.getFirstNativeHeader("Authorization"));
        return socket;
    }

    /** Builds an inbound frame for the specified transport and optional subscription destination. */
    private Message<byte[]> frame(StompCommand command, String session, String destination) {
        log.debug("Creating test STOMP frame. command={}", command);
        var accessor = StompHeaderAccessor.create(command);
        accessor.setSessionId(session);
        if (destination != null) {
            accessor.setDestination(destination);
        }
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    /** Rejects missing authentication before subscribing and missing tokens during CONNECT. */
    @Test
    void rejectsUnauthenticatedFrames() {
        log.info("Testing missing WebSocket authentication");
        assertThrows(AccessDeniedException.class, () -> security.preSend(frame(StompCommand.CONNECT, "s", null), null));
        assertThrows(AccessDeniedException.class, () -> security.preSend(
                frame(StompCommand.SUBSCRIBE, "s", "/user/queue/document-status"), null));
    }

    /** Rejects an invalid signature and strips credentials before framework error logging sees the message. */
    @Test
    void rejectsInvalidTokenWithoutRetainingCredentials() {
        log.info("Testing invalid token rejection and credential removal");
        when(decoder.decode("invalid-token")).thenThrow(new JwtException("Invalid signature"));
        var accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setSessionId("invalid-session");
        accessor.setNativeHeader("Authorization", "Bearer invalid-token");
        accessor.setLeaveMutable(true);
        var message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
        assertThrows(AccessDeniedException.class, () -> security.preSend(message, null));
        assertNull(accessor.getFirstNativeHeader("Authorization"));
        assertFalse(message.toString().contains("invalid-token"));
    }

    /** Allows only the private status destination and prohibits client publication and direct broker access. */
    @Test
    void enforcesReadOnlyPrivateSubscription() {
        log.info("Testing private read-only document subscriptions");
        connect("owner", token("owner-token", 7, "EMPLOYEE", Instant.now().plusSeconds(120)));
        assertDoesNotThrow(() -> security.preSend(frame(StompCommand.SUBSCRIBE, "owner", "/user/queue/document-status"), null));
        for (String destination : Set.of("/queue/document-status", "/topic/documents", "/user/other/queue/document-status")) {
            assertThrows(AccessDeniedException.class, () -> security.preSend(frame(StompCommand.SUBSCRIBE, "owner", destination), null));
        }
        assertThrows(AccessDeniedException.class, () -> security.preSend(
                frame(StompCommand.SEND, "owner", "/user/queue/document-status"), null));
    }

    /** Includes all owner tabs and managers while excluding unrelated employee connections. */
    @Test
    void selectsOnlyOwnerAndManagers() {
        log.info("Testing document recipient authorization");
        Jwt owner = token("owner-token", 7, "EMPLOYEE", Instant.now().plusSeconds(120));
        connect("owner-tab1", owner);
        connect("owner-tab2", owner);
        connect("manager", token("manager-token", 9, "MANAGER", Instant.now().plusSeconds(120)));
        connect("other", token("other-token", 8, "EMPLOYEE", Instant.now().plusSeconds(120)));
        assertEquals(Set.of("owner-tab1", "owner-tab2", "manager"), sessions.recipients(7L));
        sessions.closed("owner-tab1");
        assertEquals(Set.of("owner-tab2", "manager"), sessions.recipients(7L));
    }

    /** Removes revoked accounts before any further notifications can reach their sessions. */
    @Test
    void rejectsRevokedAccountOnDelivery() throws Exception {
        log.info("Testing revoked account notification suppression");
        WebSocketSession socket = connect("owner", token("owner-token", 7, "EMPLOYEE", Instant.now().plusSeconds(120)));
        when(decoder.decode("owner-token")).thenThrow(new JwtException("Account revoked"));
        assertTrue(sessions.recipients(7L).isEmpty());
        verify(socket).close(CloseStatus.POLICY_VIOLATION);
    }

    /** Closes a connected transport at token expiry without waiting for another inbound frame. */
    @Test
    void closesExpiredConnection() throws Exception {
        log.info("Testing token expiry closure");
        WebSocketSession socket = connect("owner", token("owner-token", 7, "EMPLOYEE", Instant.now().plusSeconds(2)));
        verify(socket, timeout(5000)).close(CloseStatus.POLICY_VIOLATION);
        assertTrue(sessions.recipients(7L).isEmpty());
    }

    /** Rejects tokens already expired even if decoder clock skew would otherwise accept them. */
    @Test
    void rejectsExpiredConnect() {
        log.info("Testing expired CONNECT token rejection");
        Jwt expired = token("expired", 7, "EMPLOYEE", Instant.now().minusSeconds(1));
        assertThrows(AccessDeniedException.class, () -> connect("expired-session", expired));
    }

    /** Runs real Spring STOMP routing and JSON conversion through a transport without external infrastructure. */
    @Test
    void routesStatusThroughConfiguredBroker() throws Exception {
        log.info("Testing configured STOMP transport and private JSON delivery");
        token("transport-token", 7, "EMPLOYEE", Instant.now().plusSeconds(120));
        try (var context = new org.springframework.web.context.support.GenericWebApplicationContext()) {
            context.setServletContext(new org.springframework.mock.web.MockServletContext());
            context.registerBean(WebSocketSessionService.class, () -> sessions);
            context.registerBean(WebSocketSecurityInterceptor.class, () -> security);
            new org.springframework.context.annotation.AnnotatedBeanDefinitionReader(context).register(WebSocketConfig.class);
            context.refresh();
            var mapping = context.getBean("stompWebSocketHandlerMapping", org.springframework.web.servlet.HandlerMapping.class);
            var request = new org.springframework.mock.web.MockHttpServletRequest("GET", "/ws");
            var endpoint = (org.springframework.web.socket.server.support.WebSocketHttpRequestHandler)
                    mapping.getHandler(request).getHandler();
            var handler = endpoint.getWebSocketHandler();
            var socket = mock(WebSocketSession.class);
            when(socket.getId()).thenReturn("transport-session");
            when(socket.isOpen()).thenReturn(true);
            when(socket.getAttributes()).thenReturn(new java.util.concurrent.ConcurrentHashMap<>());
            when(socket.getAcceptedProtocol()).thenReturn("v12.stomp");
            when(socket.getTextMessageSizeLimit()).thenReturn(65536);
            var subscribed = new java.util.concurrent.CountDownLatch(1);
            var inbound = context.getBean("clientInboundChannel", org.springframework.messaging.support.ExecutorSubscribableChannel.class);
            inbound.addInterceptor(new org.springframework.messaging.support.ExecutorChannelInterceptor() {
                /** Signals when private subscription translation has reached the actual broker. */
                @Override
                public void afterMessageHandled(Message<?> message, org.springframework.messaging.MessageChannel channel,
                                                org.springframework.messaging.MessageHandler subscriber, Exception exception) {
                    log.debug("Inspecting STOMP subscription completion");
                    if (subscriber instanceof org.springframework.messaging.simp.user.UserDestinationMessageHandler
                            && org.springframework.messaging.simp.SimpMessageHeaderAccessor.getMessageType(message.getHeaders())
                            == org.springframework.messaging.simp.SimpMessageType.SUBSCRIBE) {
                        subscribed.countDown();
                    }
                }
            });
            handler.afterConnectionEstablished(socket);
            handler.handleMessage(socket, new org.springframework.web.socket.TextMessage(
                    "CONNECT\naccept-version:1.2\nhost:localhost\nAuthorization:Bearer transport-token\n\n\u0000"));
            verify(socket, timeout(3000)).sendMessage(argThat(message -> message.getPayload().toString().startsWith("CONNECTED")));
            handler.handleMessage(socket, new org.springframework.web.socket.TextMessage(
                    "SUBSCRIBE\nid:status\ndestination:/user/queue/document-status\n\n\u0000"));
            assertTrue(subscribed.await(3, java.util.concurrent.TimeUnit.SECONDS));
            var payload = new org.involens_be.dto.response.DocumentStatusResponse(java.util.UUID.randomUUID(),
                    org.involens_be.common.DocumentState.PROCESSING, null, null);
            context.getBean(org.springframework.messaging.simp.SimpMessagingTemplate.class)
                    .convertAndSendToUser("transport-session", "/queue/document-status", payload);
            verify(socket, timeout(3000)).sendMessage(argThat(message -> {
                String body = message.getPayload().toString();
                return body.startsWith("MESSAGE") && body.contains("application/json")
                        && body.contains("\"currentState\":\"PROCESSING\"")
                        && body.contains(payload.documentId().toString());
            }));
            handler.afterConnectionClosed(socket, CloseStatus.NORMAL);
            assertTrue(sessions.recipients(7L).isEmpty());
        }
    }
}
