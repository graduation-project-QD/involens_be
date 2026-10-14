package org.involens_be.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.involens_be.common.AiStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "extraction_results")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class ExtractionResultEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "document_id", nullable = false, unique = true)
    private UUID documentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "ai_status", nullable = false)
    private AiStatus aiStatus;

    @Builder.Default
    @Column(name = "retry_count", nullable = false)
    private Integer retryCount = 0;

    @Builder.Default
    @Column(name = "human_review_required", nullable = false)
    private Boolean humanReviewRequired = false;

    @Column(name = "overall_confidence", precision = 5, scale = 4)
    private BigDecimal overallConfidence;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_json", columnDefinition = "jsonb")
    private Map<String, Object> rawJson;

    @Column(name = "error_code")
    private String errorCode;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Column(name = "processing_started_at")
    private LocalDateTime processingStartedAt;

    @Column(name = "processing_finished_at")
    private LocalDateTime processingFinishedAt;
}
