package org.involens_be.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.involens_be.common.DocumentState;
import org.involens_be.common.CommonStatus;
import org.involens_be.common.UploadStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "documents")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "original_filename", nullable = false)
    private String originalFilename;

    @Column(name = "mime_type", nullable = false)
    private String mimeType;

    @Column(name = "file_size", nullable = false)
    private Long fileSize;

    @Column(name = "object_key")
    private String objectKey;

    @Column(name = "checksum_sha256")
    private String checksumSha256;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CommonStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "upload_status", nullable = false)
    private UploadStatus uploadStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "current_state", nullable = false)
    private DocumentState currentState;

    @Column(name = "uploaded_by", nullable = false)
    private Long uploadedById;

    @Column(name = "error_code")
    private String errorCode;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @CreationTimestamp
    @Column(name = "uploaded_at", nullable = false, updatable = false)
    private OffsetDateTime uploadedAt;
}
