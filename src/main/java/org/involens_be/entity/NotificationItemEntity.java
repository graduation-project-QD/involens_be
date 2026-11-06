package org.involens_be.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Links a grouped notification to a document or a rolled-back upload file. */
@Entity
@Table(name = "notification_items", uniqueConstraints = @UniqueConstraint(
        name = "uk_notification_item_source", columnNames = {"notification_id", "source_id"}))
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class NotificationItemEntity {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(nullable = false)
    private UUID notificationId;
    @Column(nullable = false)
    private UUID sourceId;
    private UUID documentId;
    private String filename;
    private String errorCode;
    @Column(columnDefinition = "text")
    private String errorMessage;
    @Column(nullable = false)
    private boolean active;
    @Column(nullable = false)
    private OffsetDateTime createdAt;
}
