package org.involens_be.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.involens_be.config.OpenApiConfig;
import org.involens_be.dto.response.BaseResponse;
import org.involens_be.dto.response.NotificationResponse;
import org.involens_be.service.NotificationService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.UUID;

/** Exposes uploader-only bell history and read actions through authenticated REST requests. */
@RestController @RequestMapping("/api/v1/notifications") @RequiredArgsConstructor @Slf4j
@Tag(name = "Notifications", description = "Uploader bell notifications and read state")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH_SCHEME)
public class NotificationController {
    private final NotificationService service;

    /** Returns paginated bell entries, retaining read history and hiding resolved review entries. */
    @GetMapping
    public BaseResponse<NotificationResponse.Listing> list(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "1") int page, @RequestParam(name = "page_size", defaultValue = "8") int pageSize) {
        log.debug("Received notification list request");
        return BaseResponse.success(service.list(owner(jwt), page, pageSize));
    }

    /** Returns unread badge and outcome totals for the authenticated uploader. */
    @GetMapping("/summary")
    public BaseResponse<NotificationResponse.Summary> summary(@AuthenticationPrincipal Jwt jwt) {
        log.debug("Received notification summary request");
        return BaseResponse.success(service.summary(owner(jwt)));
    }

    /** Returns the exact documents represented by a bell entry for navigation and error details. */
    @GetMapping("/{id}/items")
    public BaseResponse<NotificationResponse.ItemListing> items(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @RequestParam(defaultValue = "1") int page, @RequestParam(name = "page_size", defaultValue = "8") int pageSize) {
        log.debug("Received notification items request. notificationId={}", id);
        return BaseResponse.success(service.listItems(owner(jwt), id, page, pageSize));
    }

    /** Clears the selected entry's unread dot while keeping its history. */
    @PatchMapping("/{id}/read")
    public BaseResponse<Void> read(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        log.info("Received mark notification read request. notificationId={}", id);
        service.markRead(owner(jwt), id);
        return BaseResponse.success(null);
    }

    /** Clears all current unread dots and the badge without deleting notification history. */
    @PatchMapping("/read-all")
    public BaseResponse<Void> readAll(@AuthenticationPrincipal Jwt jwt) {
        log.info("Received mark all notifications read request");
        service.markAllRead(owner(jwt));
        return BaseResponse.success(null);
    }

    /** Extracts the recipient from the verified token instead of accepting a client-selected user ID. */
    private Long owner(Jwt jwt) {
        log.debug("Resolving notification caller from token");
        return ((Number) jwt.getClaim("userId")).longValue();
    }

    /** Preserves the application's error envelope for notification validation and ownership failures. */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<BaseResponse<Void>> handleNotificationError(ResponseStatusException exception) {
        log.warn("Notification request rejected. status={}, reason={}", exception.getStatusCode(), exception.getReason());
        return ResponseEntity.status(exception.getStatusCode()).body(BaseResponse.error(null,
                exception.getStatusCode().value() == 404 ? "NOTIFICATION_NOT_FOUND" : "INVALID_NOTIFICATION_REQUEST",
                exception.getReason()));
    }
}
