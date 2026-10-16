package org.involens_be.service;

import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.involens_be.common.CommonStatus;
import org.involens_be.common.DocumentState;
import org.involens_be.common.UploadStatus;
import org.involens_be.common.UserRole;
import org.involens_be.dto.response.DocumentUploadItemResponse;
import org.involens_be.dto.response.DocumentUploadResponse;
import org.involens_be.entity.DocumentEntity;
import org.involens_be.exception.DocumentNotFoundException;
import org.involens_be.exception.DocumentRetryException;
import org.involens_be.exception.DocumentUploadBatchException;
import org.involens_be.mapper.DocumentMapper;
import org.involens_be.repository.DocumentRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.io.OutputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Stores document uploads and dispatches AI extraction requests after their metadata is committed. */
@Slf4j(topic = "DOCUMENT-UPLOAD-SERVICE")
@Service
@RequiredArgsConstructor
public class DocumentUploadService {

    private static final long MAX_FILE_SIZE_BYTES = 10L * 1024 * 1024;
    private static final String UNSUPPORTED_FORMAT_CODE = "UNSUPPORTED_FORMAT";
    private static final String FILE_TOO_LARGE_CODE = "FILE_TOO_LARGE";
    private static final String EMPTY_FILE_CODE = "EMPTY_FILE";
    private static final String DUPLICATE_FILE_CODE = "DUPLICATE_FILE";
    private static final String DUPLICATE_FILE_MESSAGE = "Tệp có nội dung trùng với tài liệu đã được tải lên";
    private static final String SUPPORTED_FILES_MESSAGE = "Chỉ hỗ trợ JPG, JPEG, PNG, PDF (tối đa 10MB)";
    private static final Map<String, Set<String>> SUPPORTED_TYPES = Map.of(
            "jpg", Set.of("image/jpeg"),
            "jpeg", Set.of("image/jpeg"),
            "png", Set.of("image/png"),
            "pdf", Set.of("application/pdf"));

    private final DocumentRepository documentRepository;
    private final DocumentStatusNotificationService statusNotifications;
    private final MinioClient minioClient;
    private final DocumentMapper documentMapper;
    private final ExtractionMessageService extractionMessageService;

    @Value("${app.minio.bucket}")
    private String bucket;

    /**
     * Resends a visible active document's existing file to AI and records PROCESSING or PENDING_FAILURE.
     * Locks the document and commits dispatch failure state before reporting the retry error; preserves stored data.
     */
    @Transactional(noRollbackFor = DocumentRetryException.class)
    public DocumentUploadItemResponse retryExtraction(UUID documentId, Long userId, UserRole role) {
        log.info("Starting document extraction retry. documentId={}, userId={}, role={}", documentId, userId, role);
        DocumentEntity document = documentRepository.findVisibleDocumentForUpdate(
                        documentId, userId, role == UserRole.MANAGER)
                .orElseThrow(() -> {
                    log.warn("Document extraction retry rejected: active document is not visible. documentId={}, userId={}",
                            documentId, userId);
                    return new DocumentNotFoundException();
                });
        validateRetryDocument(document);
        DocumentState previousState = document.getCurrentState();
        boolean published = extractionMessageService.publishExtract(document);
        DocumentState dispatchState = published ? DocumentState.PROCESSING : DocumentState.PENDING_FAILURE;
        try {
            document.setCurrentState(dispatchState);
            document.setErrorCode(published ? null : ExtractionMessageService.DISPATCH_FAILED_CODE);
            document.setErrorMessage(published ? null : ExtractionMessageService.DISPATCH_FAILED_MESSAGE);
            documentRepository.saveAndFlush(document);
        } catch (RuntimeException exception) {
            if (TransactionSynchronizationManager.isActualTransactionActive()) {
                TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            }
            log.error("Extraction retry state could not be saved. documentId={}, state={}",
                    documentId, dispatchState, exception);
            throw new DocumentRetryException(DocumentRetryException.Reason.PERSISTENCE_FAILED,
                    "Không thể lưu trạng thái tài liệu sau khi gửi yêu cầu sang AI.", exception);
        }
        statusNotifications.afterCommit(document, previousState);
        if (!published) {
            log.warn("Document extraction retry failed; pending failure state saved. documentId={}", documentId);
            throw new DocumentRetryException(DocumentRetryException.Reason.DISPATCH_FAILED,
                    "Không thể gửi lại tài liệu sang AI. Vui lòng kiểm tra RabbitMQ và thử lại.");
        }
        DocumentUploadItemResponse response = documentMapper.toUploadItemResponse(document);
        log.info("Document extraction retry published and processing state saved. documentId={}, userId={}",
                documentId, userId);
        return response;
    }

    /** Rejects extraction retries unless the document is waiting or failed and has a successfully stored file. */
    private void validateRetryDocument(DocumentEntity document) {
        log.debug("Validating document extraction retry. documentId={}, state={}, uploadStatus={}",
                document.getId(), document.getCurrentState(), document.getUploadStatus());
        if (document.getCurrentState() != DocumentState.UPLOADED
                && document.getCurrentState() != DocumentState.EXTRACT_FAILED
                && document.getCurrentState() != DocumentState.PENDING_FAILURE) {
            log.warn("Document extraction retry rejected for its processing state. documentId={}, state={}",
                    document.getId(), document.getCurrentState());
            throw new DocumentRetryException(DocumentRetryException.Reason.NOT_ALLOWED,
                    "Chỉ được gửi lại tài liệu ở trạng thái UPLOADED, EXTRACT_FAILED hoặc PENDING_FAILURE.");
        }
        if (document.getUploadStatus() != UploadStatus.UPLOADED || !StringUtils.hasText(document.getObjectKey())) {
            log.warn("Document extraction retry rejected because stored file metadata is unavailable. documentId={}, uploadStatus={}",
                    document.getId(), document.getUploadStatus());
            throw new DocumentRetryException(DocumentRetryException.Reason.NOT_ALLOWED,
                    "Tài liệu chưa được tải lên thành công hoặc không có objectKey.");
        }
        log.debug("Document extraction retry validated. documentId={}", document.getId());
    }

    /**
     * Validates and stores a document batch for the authenticated user, preserving validation failures
     * as document records and rolling back the complete batch after a storage or database failure.
     *
     * @param files files submitted in the multipart request
     * @param uploadedById authenticated user's database identifier
     * @return persisted upload result for every submitted file
     */
    @Transactional
    public DocumentUploadResponse uploadDocuments(List<MultipartFile> files, Long uploadedById) {
        log.info("Starting document batch upload. uploadedById={}, fileCount={}", uploadedById, files.size());
        List<DocumentEntity> documents = new ArrayList<>();
        List<String> uploadedObjectKeys = new ArrayList<>();
        Set<String> acceptedChecksums = new HashSet<>();

        try {
            for (MultipartFile file : files) {
                DocumentEntity document = createDocument(file, uploadedById);
                if (document.getUploadStatus() == UploadStatus.UPLOADING) {
                    String checksum = calculateChecksum(file);
                    document.setChecksumSha256(checksum);
                }
                documents.add(document);
            }

            documents.stream()
                    .filter(document -> document.getUploadStatus() == UploadStatus.UPLOADING)
                    .map(DocumentEntity::getChecksumSha256)
                    .distinct()
                    .sorted()
                    .forEach(documentRepository::acquireChecksumLock);

            for (int index = 0; index < documents.size(); index++) {
                DocumentEntity document = documents.get(index);
                if (document.getUploadStatus() == UploadStatus.UPLOADING) {
                    String checksum = document.getChecksumSha256();
                    boolean duplicateInBatch = !acceptedChecksums.add(checksum);
                    boolean duplicateInDatabase = documentRepository
                            .existsByChecksumSha256AndStatusAndUploadStatus(
                                    checksum, CommonStatus.ACTIVE, UploadStatus.UPLOADED);
                    if (duplicateInBatch || duplicateInDatabase) {
                        log.warn("Document upload rejected because its content is duplicated. filename={}, checksum={}, duplicateInBatch={}",
                                document.getOriginalFilename(), checksum, duplicateInBatch);
                        markFailedDocument(document, DUPLICATE_FILE_CODE, DUPLICATE_FILE_MESSAGE);
                    } else {
                        storeFile(files.get(index), document);
                        uploadedObjectKeys.add(document.getObjectKey());
                    }
                }
            }

            List<DocumentEntity> savedDocuments = documentRepository.saveAllAndFlush(documents);
            savedDocuments.forEach(document -> statusNotifications.afterCommit(document, null));
            List<DocumentEntity> uploadedDocuments = savedDocuments.stream()
                    .filter(document -> document.getUploadStatus() == UploadStatus.UPLOADED)
                    .toList();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                /** Sends extraction requests only after the uploaded document records are committed. */
                @Override
                public void afterCommit() {
                    log.info("Dispatching extraction requests after document batch commit. count={}", uploadedDocuments.size());
                    uploadedDocuments.forEach(extractionMessageService::sendExtract);
                    log.info("Extraction request dispatch completed. count={}", uploadedDocuments.size());
                }
            });
            log.info("Document batch uploaded successfully. uploadedById={}, fileCount={}",
                    uploadedById, savedDocuments.size());
            return DocumentUploadResponse.builder()
                    .items(savedDocuments.stream().map(documentMapper::toUploadItemResponse).toList())
                    .build();
        } catch (Exception exception) {
            log.error("Document batch upload failed; starting object storage compensation. uploadedById={}",
                    uploadedById, exception);
            removeUploadedObjects(uploadedObjectKeys);
            throw new DocumentUploadBatchException("Không thể tải lên tài liệu. Toàn bộ batch đã được rollback", exception);
        }
    }

    /**
     * Creates the initial persistence model and records a business validation failure when the file
     * is empty, too large, or does not have a supported extension and MIME type combination.
     *
     * @param file submitted multipart file
     * @param uploadedById authenticated user's database identifier
     * @return document ready either for object storage or direct failed-record persistence
     */
    private DocumentEntity createDocument(MultipartFile file, Long uploadedById) {
        log.debug("Preparing document upload metadata. uploadedById={}, filename={}",
                uploadedById, file.getOriginalFilename());
        String originalFilename = normalizeFilename(file.getOriginalFilename());
        DocumentEntity.DocumentEntityBuilder builder = DocumentEntity.builder()
                .originalFilename(originalFilename)
                .mimeType(normalizeMimeType(file.getContentType()))
                .fileSize(file.getSize())
                .status(CommonStatus.ACTIVE)
                .uploadedById(uploadedById);

        if (file.isEmpty()) {
            log.warn("Document validation failed because the file is empty. filename={}", originalFilename);
            return failedDocument(builder, EMPTY_FILE_CODE, "Tệp tải lên không được để trống");
        }
        if (file.getSize() > MAX_FILE_SIZE_BYTES) {
            log.warn("Document validation failed because the file exceeds 10MB. filename={}, fileSize={}",
                    originalFilename, file.getSize());
            return failedDocument(builder, FILE_TOO_LARGE_CODE, SUPPORTED_FILES_MESSAGE);
        }
        if (!isSupported(originalFilename, file.getContentType())) {
            log.warn("Document validation failed because the format is unsupported. filename={}, mimeType={}",
                    originalFilename, file.getContentType());
            return failedDocument(builder, UNSUPPORTED_FORMAT_CODE, SUPPORTED_FILES_MESSAGE);
        }

        log.debug("Document passed upload validation. filename={}", originalFilename);
        return builder
                .uploadStatus(UploadStatus.UPLOADING)
                .currentState(DocumentState.UPLOADED)
                .build();
    }

    /**
     * Uploads one validated and non-duplicate file to MinIO and marks its metadata as uploaded.
     *
     * @param file validated multipart file
     * @param document document metadata to complete after storage
     * @throws Exception when reading or storing the file fails
     */
    private void storeFile(MultipartFile file, DocumentEntity document) throws Exception {
        String objectKey = buildObjectKey(document.getOriginalFilename());
        log.info("Uploading document to object storage. objectKey={}, filename={}",
                objectKey, document.getOriginalFilename());
        try (InputStream input = file.getInputStream()) {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .stream(input, file.getSize(), -1)
                    .contentType(document.getMimeType())
                    .build());
        }
        document.setObjectKey(objectKey);
        document.setUploadStatus(UploadStatus.UPLOADED);
        log.info("Document uploaded to object storage successfully. objectKey={}", objectKey);
    }

    /**
     * Calculates the SHA-256 fingerprint used to identify identical file content before object storage is changed.
     *
     * @param file validated multipart file
     * @return lowercase hexadecimal SHA-256 checksum
     * @throws Exception when the file cannot be read or SHA-256 is unavailable
     */
    private String calculateChecksum(MultipartFile file) throws Exception {
        log.debug("Calculating document checksum. filename={}", file.getOriginalFilename());
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new DigestInputStream(file.getInputStream(), digest)) {
            input.transferTo(OutputStream.nullOutputStream());
        }
        String checksum = HexFormat.of().formatHex(digest.digest());
        log.debug("Document checksum calculated. filename={}, checksum={}", file.getOriginalFilename(), checksum);
        return checksum;
    }

    /**
     * Removes objects written by the current batch so an infrastructure failure does not leave orphaned files.
     *
     * @param objectKeys object keys successfully written before the batch failed
     */
    private void removeUploadedObjects(List<String> objectKeys) {
        for (String objectKey : objectKeys) {
            try {
                log.info("Removing uploaded object during batch compensation. objectKey={}", objectKey);
                minioClient.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(objectKey).build());
                log.info("Uploaded object removed during batch compensation. objectKey={}", objectKey);
            } catch (Exception cleanupException) {
                log.error("Failed to remove object during batch compensation. objectKey={}",
                        objectKey, cleanupException);
            }
        }
    }

    /**
     * Builds a failed document model for a file rejected by business validation.
     *
     * @param builder document metadata builder
     * @param errorCode stable validation error code
     * @param errorMessage client-facing validation explanation
     * @return failed document metadata without an object storage key
     */
    private DocumentEntity failedDocument(
            DocumentEntity.DocumentEntityBuilder builder,
            String errorCode,
            String errorMessage) {
        log.debug("Preparing failed document metadata. errorCode={}", errorCode);
        return builder
                .uploadStatus(UploadStatus.FAILED)
                .currentState(DocumentState.UPLOADED_FAILED)
                .errorCode(errorCode)
                .errorMessage(errorMessage)
                .build();
    }

    /**
     * Converts a prepared document into a persisted per-file validation failure without uploading an object.
     *
     * @param document prepared document metadata
     * @param errorCode stable validation error code
     * @param errorMessage client-facing validation explanation
     */
    private void markFailedDocument(DocumentEntity document, String errorCode, String errorMessage) {
        log.debug("Marking document upload as failed. filename={}, errorCode={}",
                document.getOriginalFilename(), errorCode);
        document.setUploadStatus(UploadStatus.FAILED);
        document.setCurrentState(DocumentState.UPLOADED_FAILED);
        document.setErrorCode(errorCode);
        document.setErrorMessage(errorMessage);
        log.debug("Document upload marked as failed. filename={}, errorCode={}",
                document.getOriginalFilename(), errorCode);
    }

    /**
     * Verifies that both the file extension and supplied MIME type identify the same supported format.
     *
     * @param filename normalized original filename
     * @param contentType MIME type supplied for the multipart part
     * @return true when the extension and MIME type are supported together
     */
    private boolean isSupported(String filename, String contentType) {
        log.debug("Checking document format. filename={}, mimeType={}", filename, contentType);
        int extensionSeparator = filename.lastIndexOf('.');
        if (extensionSeparator < 0 || extensionSeparator == filename.length() - 1 || contentType == null) {
            log.debug("Document format cannot be identified. filename={}, mimeType={}", filename, contentType);
            return false;
        }
        String extension = filename.substring(extensionSeparator + 1).toLowerCase(Locale.ROOT);
        Set<String> mimeTypes = SUPPORTED_TYPES.get(extension);
        boolean supported = mimeTypes != null && mimeTypes.contains(normalizeMimeType(contentType));
        log.debug("Document format checked. filename={}, supported={}", filename, supported);
        return supported;
    }

    /**
     * Converts a client filename into a safe display value without any submitted path components.
     *
     * @param originalFilename filename supplied by the multipart client
     * @return normalized filename used in persistence and responses
     */
    private String normalizeFilename(String originalFilename) {
        log.debug("Normalizing submitted document filename. filename={}", originalFilename);
        if (originalFilename == null || originalFilename.isBlank()) {
            return "unnamed";
        }
        return originalFilename.replace('\\', '/').substring(originalFilename.replace('\\', '/').lastIndexOf('/') + 1);
    }

    /**
     * Normalizes MIME values before validation and persistence.
     *
     * @param contentType multipart MIME type, possibly null or mixed case
     * @return normalized MIME type or application/octet-stream when absent
     */
    private String normalizeMimeType(String contentType) {
        log.debug("Normalizing document MIME type. mimeType={}", contentType);
        return contentType == null || contentType.isBlank()
                ? "application/octet-stream"
                : contentType.toLowerCase(Locale.ROOT);
    }

    /**
     * Creates a collision-resistant MinIO key while retaining the supported file extension.
     *
     * @param filename validated original filename
     * @return object key namespaced by UTC year and month
     */
    private String buildObjectKey(String filename) {
        log.debug("Creating document storage key. filename={}", filename);
        int extensionSeparator = filename.lastIndexOf('.');
        String extension = filename.substring(extensionSeparator).toLowerCase(Locale.ROOT);
        OffsetDateTime now = OffsetDateTime.now(Clock.systemUTC());
        return "%d/%02d/%s%s".formatted(now.getYear(), now.getMonthValue(), UUID.randomUUID(), extension);
    }

}
