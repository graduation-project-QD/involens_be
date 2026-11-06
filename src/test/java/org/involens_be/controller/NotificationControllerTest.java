package org.involens_be.controller;

import lombok.extern.slf4j.Slf4j;
import org.involens_be.common.NotificationType;
import org.involens_be.dto.response.NotificationResponse;
import org.involens_be.service.NotificationService;
import org.junit.jupiter.api.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;
import java.time.OffsetDateTime;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Exercises the FE REST contract, JWT ownership and body-free read actions. */
@Slf4j
class NotificationControllerTest {
    private NotificationService service;
    private MockMvc mvc;

    /** Supplies an authenticated uploader to the controller using Spring's principal resolver. */
    @BeforeEach void setup() {
        log.info("Preparing notification controller tests");
        service = mock(NotificationService.class);
        mvc = MockMvcBuilders.standaloneSetup(new NotificationController(service))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build();
        Jwt jwt = Jwt.withTokenValue("test").header("alg", "HS256").claim("userId", 12L).build();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(jwt, null, List.of()));
    }

    /** Clears the test caller so authentication cannot leak across controller tests. */
    @AfterEach void cleanup() {
        log.info("Clearing notification test caller");
        SecurityContextHolder.clearContext();
    }

    /** Returns the documented count and read flag while ignoring any client-selected recipient. */
    @Test void listsOwnEntriesAndSummary() throws Exception {
        log.info("Testing notification JSON list and summary contract");
        var now = OffsetDateTime.now();
        when(service.list(12L, 1, 8)).thenReturn(new NotificationResponse.Listing(List.of(
                new NotificationResponse(UUID.randomUUID(), NotificationType.SUCCESS, 8, false, null, now, now)), 1, 1, 8));
        when(service.summary(12L)).thenReturn(new NotificationResponse.Summary(1, 8, 0, 0));
        mvc.perform(get("/api/v1/notifications?recipientId=99"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value(0))
                .andExpect(jsonPath("$.data.items[0].type").value("SUCCESS"))
                .andExpect(jsonPath("$.data.items[0].count").value(8))
                .andExpect(jsonPath("$.data.items[0].read").value(false))
                .andExpect(jsonPath("$.data.pageSize").value(8));
        mvc.perform(get("/api/v1/notifications/summary"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.unreadCount").value(1))
                .andExpect(jsonPath("$.data.successCount").value(8));
    }

    /** Accepts read actions without bodies and binds both operations to the token's uploader. */
    @Test void acceptsBodyFreeReadRequests() throws Exception {
        log.info("Testing notification read requests");
        UUID id = UUID.randomUUID();
        mvc.perform(patch("/api/v1/notifications/{id}/read", id)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value(0));
        mvc.perform(patch("/api/v1/notifications/read-all")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value(0));
        verify(service).markRead(12L, id);
        verify(service).markAllRead(12L);
    }

    /** Uses the standard error envelope for inaccessible entries and invalid pagination. */
    @Test void reportsNotFoundAndValidationErrors() throws Exception {
        log.info("Testing notification error contract");
        UUID id = UUID.randomUUID();
        when(service.listItems(12L, id, 1, 8)).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Notification not found"));
        when(service.list(12L, 0, 8)).thenThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid page"));
        mvc.perform(get("/api/v1/notifications/{id}/items", id)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOTIFICATION_NOT_FOUND"));
        mvc.perform(get("/api/v1/notifications?page=0")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_NOTIFICATION_REQUEST"));
    }
}
