package org.involens_be.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.involens_be.common.ReviewStatus;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "review_tasks")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class ReviewTaskEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "extraction_result_id", nullable = false)
    private UUID extractionResultId;

    @Column(nullable = false)
    private String reason;

    @Column(nullable = false)
    private Integer priority;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReviewStatus status;

    @Column(name = "assigned_to")
    private UUID assignedToId;

    @Column(name = "review_note", columnDefinition = "text")
    private String reviewNote;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;
}
