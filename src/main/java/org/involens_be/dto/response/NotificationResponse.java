package org.involens_be.dto.response;

import com.google.gson.annotations.Expose;
import org.involens_be.common.NotificationType;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Provides grouped bell entries and paginated related files without exposing persistence entities. */
public record NotificationResponse(@Expose UUID id, @Expose NotificationType type, @Expose long count,
        @Expose boolean read, @Expose OffsetDateTime readAt, @Expose OffsetDateTime createdAt,
        @Expose OffsetDateTime updatedAt) {
    public record Summary(@Expose long unreadCount, @Expose long successCount,
                          @Expose long reviewRequiredCount, @Expose long failureCount) { }
    public record Listing(@Expose List<NotificationResponse> items, @Expose long total,
                          @Expose int page, @Expose int pageSize) { }
    public record Item(@Expose UUID id, @Expose UUID documentId, @Expose String filename,
                       @Expose String errorCode, @Expose String errorMessage) { }
    public record ItemListing(@Expose List<Item> items, @Expose long total,
                              @Expose int page, @Expose int pageSize) { }
    public record Change(@Expose String type, @Expose UUID notificationId, @Expose long unreadCount) { }
}
