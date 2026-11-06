package org.involens_be.entity;

import jakarta.persistence.*;
import lombok.*;
import org.involens_be.common.NotificationType;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Persists one grouped bell entry owned by one uploader. */
@Entity
@Table(name = "notifications")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class NotificationEntity {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(nullable = false)
    private Long recipientId;
    @Enumerated(EnumType.STRING) @Column(nullable = false)
    private NotificationType type;
    @Column(nullable = false)
    private long itemCount;
    private OffsetDateTime readAt;
    @Column(nullable = false)
    private boolean visible;
    @Column(nullable = false)
    private OffsetDateTime createdAt;
    @Column(nullable = false)
    private OffsetDateTime updatedAt;
}
