package org.involens_be.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.involens_be.common.ExportFormat;
import org.involens_be.common.ExportStatus;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "export_jobs")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class ExportJobEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "requested_by", nullable = false)
    private UUID requestedById;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ExportFormat format;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> filters;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ExportStatus status;

    @Column(name = "object_key")
    private String objectKey;

    @Column(name = "row_count")
    private Integer rowCount;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
