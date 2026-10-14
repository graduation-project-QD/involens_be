package org.involens_be.service;

import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
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
import org.involens_be.repository.DocumentRepository;
import org.involens_be.mapper.DocumentMapper;
import org.mapstruct.factory.Mappers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@Slf4j
@ExtendWith(MockitoExtension.class)
class DocumentServiceTest {

    @Mock
    private DocumentRepository documentRepository;

    @Mock
    private MinioClient minioClient;

    @Mock
    private ExtractionMessageService extractionMessageService;

    private DocumentUploadService documentUploadService;

    /** Initializes upload collaborators and transaction callbacks without external connections. */
    @BeforeEach
    void setUp() {
        log.info("Setting up document upload service test");
        TransactionSynchronizationManager.initSynchronization();
        documentUploadService = new DocumentUploadService(documentRepository, minioClient,
                Mappers.getMapper(DocumentMapper.class), extractionMessageService);
        ReflectionTestUtils.setField(documentUploadService, "bucket", "test-documents");
        log.info("Document upload service test setup completed");
    }

    /** Clears transaction callbacks so one upload test cannot dispatch another test's documents. */
    @AfterEach
    void cleanUp() {
        log.info("Clearing document upload transaction callbacks");
        TransactionSynchronizationManager.clearSynchronization();
        log.info("Document upload transaction callbacks cleared");
    }

    /** Verifies that unsupported files are persisted as failed without storage or AI dispatch after commit. */
    @Test
    void uploadDocumentsPersistsUnsupportedFileAsFailed() throws Exception {
        log.info("Testing unsupported document upload validation");
        MockMultipartFile file = new MockMultipartFile(
                "files", "report.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "invalid".getBytes());
        when(documentRepository.saveAllAndFlush(any())).thenAnswer(invocation -> {
            List<DocumentEntity> documents = invocation.getArgument(0);
            documents.forEach(document -> {
                document.setId(UUID.randomUUID());
                document.setUploadedAt(OffsetDateTime.now(ZoneOffset.UTC));
            });
            return documents;
        });

        DocumentUploadResponse response = documentUploadService.uploadDocuments(List.of(file), 12L);

        assertEquals(1, response.getItems().size());
        assertEquals(UploadStatus.FAILED, response.getItems().getFirst().getUploadStatus());
        assertEquals(DocumentState.UPLOADED_FAILED, response.getItems().getFirst().getCurrentState());
        assertEquals("UNSUPPORTED_FORMAT", response.getItems().getFirst().getErrorCode());
        assertNull(response.getItems().getFirst().getUploadedAt());
        verify(minioClient, never()).putObject(any(PutObjectArgs.class));
        verifyNoInteractions(extractionMessageService);
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        verifyNoInteractions(extractionMessageService);
        log.info("Unsupported document upload validation test completed successfully");
    }

    /** Verifies that persistence failure removes uploaded objects and registers no AI dispatch callback. */
    @Test
    void uploadDocumentsCompensatesMinioAfterPersistenceFailure() throws Exception {
        log.info("Testing document batch compensation after persistence failure");
        MockMultipartFile file = new MockMultipartFile(
                "files", "receipt.jpg", "image/jpeg", "image-data".getBytes());
        when(documentRepository.saveAllAndFlush(any())).thenThrow(new IllegalStateException("database unavailable"));

        assertThrows(
                DocumentUploadBatchException.class,
                () -> documentUploadService.uploadDocuments(List.of(file), 12L));

        verify(minioClient).putObject(any(PutObjectArgs.class));
        verify(minioClient).removeObject(any(RemoveObjectArgs.class));
        assertTrue(TransactionSynchronizationManager.getSynchronizations().isEmpty());
        verifyNoInteractions(extractionMessageService);
        log.info("Document batch compensation test completed successfully");
    }

    /** Verifies that persisted duplicate content is rejected without uploading or requesting AI extraction. */
    @Test
    void uploadDocumentsRejectsChecksumAlreadyUploaded() throws Exception {
        log.info("Testing duplicate document rejection against persisted checksums");
        MockMultipartFile file = new MockMultipartFile(
                "files", "renamed-receipt.jpg", "image/jpeg", "same-image-data".getBytes());
        when(documentRepository.existsByChecksumSha256AndStatusAndUploadStatus(
                anyString(), eq(org.involens_be.common.CommonStatus.ACTIVE), eq(UploadStatus.UPLOADED)))
                .thenReturn(true);
        when(documentRepository.saveAllAndFlush(any())).thenAnswer(invocation -> {
            List<DocumentEntity> documents = invocation.getArgument(0);
            documents.forEach(document -> document.setId(UUID.randomUUID()));
            return documents;
        });

        DocumentUploadResponse response = documentUploadService.uploadDocuments(List.of(file), 12L);

        assertEquals(UploadStatus.FAILED, response.getItems().getFirst().getUploadStatus());
        assertEquals("DUPLICATE_FILE", response.getItems().getFirst().getErrorCode());
        verify(minioClient, never()).putObject(any(PutObjectArgs.class));
        verifyNoInteractions(extractionMessageService);
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        verifyNoInteractions(extractionMessageService);
        log.info("Persisted duplicate document rejection test completed successfully");
    }

    /** Verifies that only the first copy is uploaded and sent to AI, with dispatch deferred until commit. */
    @Test
    void uploadDocumentsRejectsLaterDuplicateInSameBatch() throws Exception {
        log.info("Testing duplicate document rejection inside one batch");
        MockMultipartFile first = new MockMultipartFile(
                "files", "first.pdf", "application/pdf", "same-pdf-data".getBytes());
        MockMultipartFile second = new MockMultipartFile(
                "files", "second.pdf", "application/pdf", "same-pdf-data".getBytes());
        when(documentRepository.saveAllAndFlush(any())).thenAnswer(invocation -> {
            List<DocumentEntity> documents = invocation.getArgument(0);
            documents.forEach(document -> {
                document.setId(UUID.randomUUID());
                document.setUploadedAt(OffsetDateTime.now(ZoneOffset.UTC));
            });
            return documents;
        });

        DocumentUploadResponse response = documentUploadService.uploadDocuments(List.of(first, second), 12L);

        assertEquals(UploadStatus.UPLOADED, response.getItems().get(0).getUploadStatus());
        assertEquals(UploadStatus.FAILED, response.getItems().get(1).getUploadStatus());
        assertEquals("DUPLICATE_FILE", response.getItems().get(1).getErrorCode());
        verify(minioClient, times(1)).putObject(any(PutObjectArgs.class));
        verifyNoInteractions(extractionMessageService);
        assertEquals(1, TransactionSynchronizationManager.getSynchronizations().size());

        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);

        ArgumentCaptor<DocumentEntity> extractionDocument = ArgumentCaptor.forClass(DocumentEntity.class);
        verify(extractionMessageService).sendExtract(extractionDocument.capture());
        assertEquals(response.getItems().getFirst().getId(), extractionDocument.getValue().getId());
        assertEquals("first.pdf", extractionDocument.getValue().getOriginalFilename());
        assertEquals(UploadStatus.UPLOADED, extractionDocument.getValue().getUploadStatus());
        verifyNoMoreInteractions(extractionMessageService);
        log.info("In-batch duplicate document rejection test completed successfully");
    }

    /** Verifies that an employee resends their existing uploaded file without writing MinIO or replacing metadata. */
    @Test
    void retryExtractionResendsUploadedDocumentWithinEmployeeScope() {
        log.info("Testing employee extraction retry for an uploaded document");
        verifySuccessfulRetry(DocumentState.UPLOADED, UserRole.EMPLOYEE, false);
        log.info("Employee extraction retry test completed successfully");
    }

    /** Verifies that a manager can resend a failed extraction while retaining the existing file and document ID. */
    @Test
    void retryExtractionResendsFailedDocumentWithinManagerScope() {
        log.info("Testing manager extraction retry for a failed document");
        verifySuccessfulRetry(DocumentState.EXTRACT_FAILED, UserRole.MANAGER, true);
        log.info("Manager extraction retry test completed successfully");
    }

    /** Verifies a previous dispatch failure can be retried using the existing stored file. */
    @Test
    void retryExtractionResendsPendingFailureDocument() {
        log.info("Testing extraction retry for pending failure");
        verifySuccessfulRetry(DocumentState.PENDING_FAILURE, UserRole.EMPLOYEE, false);
        log.info("Pending failure extraction retry test completed successfully");
    }

    /** Verifies that invisible or missing documents cannot expose their metadata or dispatch extraction. */
    @Test
    void retryExtractionRejectsDocumentOutsideVisibleScope() {
        log.info("Testing extraction retry for an invisible document");
        UUID documentId = UUID.randomUUID();
        when(documentRepository.findVisibleDocumentForUpdate(documentId, 12L, false))
                .thenReturn(Optional.empty());

        assertThrows(DocumentNotFoundException.class,
                () -> documentUploadService.retryExtraction(documentId, 12L, UserRole.EMPLOYEE));

        verify(documentRepository).findVisibleDocumentForUpdate(documentId, 12L, false);
        verifyNoMoreInteractions(documentRepository);
        verifyNoInteractions(minioClient, extractionMessageService);
        log.info("Invisible document extraction retry test completed successfully");
    }

    /** Verifies that processing, completed, review, and failed-upload documents cannot be dispatched again. */
    @Test
    void retryExtractionRejectsStatesOutsideApprovedRetryScope() {
        log.info("Testing extraction retry state validation");
        for (DocumentState state : List.of(DocumentState.PROCESSING, DocumentState.EXTRACT_SUCCESS,
                DocumentState.REVIEW_REQUIRED, DocumentState.UPLOADED_FAILED)) {
            DocumentEntity document = retryDocument(state);
            when(documentRepository.findVisibleDocumentForUpdate(document.getId(), 12L, false))
                    .thenReturn(Optional.of(document));

            DocumentRetryException exception = assertThrows(DocumentRetryException.class,
                    () -> documentUploadService.retryExtraction(document.getId(), 12L, UserRole.EMPLOYEE));

            assertEquals(DocumentRetryException.Reason.NOT_ALLOWED, exception.getReason());
            assertEquals(state, document.getCurrentState());
        }

        verify(documentRepository, never()).saveAndFlush(any(DocumentEntity.class));
        verifyNoInteractions(minioClient, extractionMessageService);
        log.info("Extraction retry state validation test completed successfully");
    }

    /** Verifies that missing or blank storage keys are rejected without attempting AI dispatch. */
    @Test
    void retryExtractionRejectsMissingObjectKey() {
        log.info("Testing extraction retry storage key validation");
        for (String objectKey : new String[] {null, "", "   "}) {
            DocumentEntity document = retryDocument(DocumentState.UPLOADED);
            document.setObjectKey(objectKey);
            when(documentRepository.findVisibleDocumentForUpdate(document.getId(), 12L, false))
                    .thenReturn(Optional.of(document));

            DocumentRetryException exception = assertThrows(DocumentRetryException.class,
                    () -> documentUploadService.retryExtraction(document.getId(), 12L, UserRole.EMPLOYEE));

            assertEquals(DocumentRetryException.Reason.NOT_ALLOWED, exception.getReason());
            assertEquals(DocumentState.UPLOADED, document.getCurrentState());
        }

        verify(documentRepository, never()).saveAndFlush(any(DocumentEntity.class));
        verifyNoInteractions(minioClient, extractionMessageService);
        log.info("Extraction retry storage key validation test completed successfully");
    }

    /** Verifies that a file not successfully uploaded cannot be retried even when it has a storage key. */
    @Test
    void retryExtractionRejectsUnsuccessfulUpload() {
        log.info("Testing extraction retry upload completion validation");
        for (UploadStatus uploadStatus : List.of(UploadStatus.UPLOADING, UploadStatus.FAILED)) {
            DocumentEntity document = retryDocument(DocumentState.UPLOADED);
            document.setUploadStatus(uploadStatus);
            when(documentRepository.findVisibleDocumentForUpdate(document.getId(), 12L, false))
                    .thenReturn(Optional.of(document));

            DocumentRetryException exception = assertThrows(DocumentRetryException.class,
                    () -> documentUploadService.retryExtraction(document.getId(), 12L, UserRole.EMPLOYEE));

            assertEquals(DocumentRetryException.Reason.NOT_ALLOWED, exception.getReason());
            assertEquals(uploadStatus, document.getUploadStatus());
            assertEquals(DocumentState.UPLOADED, document.getCurrentState());
        }

        verify(documentRepository, never()).saveAndFlush(any(DocumentEntity.class));
        verifyNoInteractions(minioClient, extractionMessageService);
        log.info("Extraction retry upload completion validation test completed successfully");
    }

    /** Verifies a failed retry commits pending failure while reporting a dispatch error. */
    @Test
    void retryExtractionCommitsPendingFailureWhenDispatchFails() {
        log.info("Testing extraction retry dispatch failure");
        DocumentEntity document = retryDocument(DocumentState.EXTRACT_FAILED);
        when(documentRepository.findVisibleDocumentForUpdate(document.getId(), 12L, false))
                .thenReturn(Optional.of(document));
        when(extractionMessageService.publishExtract(document)).thenReturn(false);

        var transactionManager = org.mockito.Mockito.mock(org.springframework.transaction.PlatformTransactionManager.class);
        var transactionStatus = org.mockito.Mockito.mock(org.springframework.transaction.TransactionStatus.class);
        when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);
        var interceptor = new org.springframework.transaction.interceptor.TransactionInterceptor(
                transactionManager, new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource());
        var proxyFactory = new org.springframework.aop.framework.ProxyFactory(documentUploadService);
        proxyFactory.addAdvice(interceptor);
        var transactionalService = (DocumentUploadService) proxyFactory.getProxy();

        DocumentRetryException exception = assertThrows(DocumentRetryException.class,
                () -> transactionalService.retryExtraction(document.getId(), 12L, UserRole.EMPLOYEE));

        assertEquals(DocumentRetryException.Reason.DISPATCH_FAILED, exception.getReason());
        assertEquals(DocumentState.PENDING_FAILURE, document.getCurrentState());
        assertEquals("EXTRACTION_DISPATCH_FAILED", document.getErrorCode());
        assertEquals("Không thể gửi tài liệu sang AI trích xuất", document.getErrorMessage());
        assertEquals(UploadStatus.UPLOADED, document.getUploadStatus());
        verify(transactionManager).commit(transactionStatus);
        verify(transactionManager, never()).rollback(any());
        verify(extractionMessageService).publishExtract(document);
        verifyNoMoreInteractions(extractionMessageService);
        verify(documentRepository).saveAndFlush(document);
        verifyNoInteractions(minioClient);
        assertTrue(TransactionSynchronizationManager.getSynchronizations().isEmpty());
        log.info("Extraction retry dispatch failure test completed successfully");
    }

    /** Verifies that failure to persist PROCESSING is reported without sending the successful message twice. */
    @Test
    void retryExtractionDoesNotRepublishAfterPersistenceFailure() {
        log.info("Testing extraction retry persistence failure after dispatch");
        DocumentEntity document = retryDocument(DocumentState.EXTRACT_FAILED);
        IllegalStateException persistenceFailure = new IllegalStateException("database unavailable");
        when(documentRepository.findVisibleDocumentForUpdate(document.getId(), 12L, false))
                .thenReturn(Optional.of(document));
        when(extractionMessageService.publishExtract(document)).thenReturn(true);
        when(documentRepository.saveAndFlush(document)).thenThrow(persistenceFailure);

        DocumentRetryException exception = assertThrows(DocumentRetryException.class,
                () -> documentUploadService.retryExtraction(document.getId(), 12L, UserRole.EMPLOYEE));

        assertEquals(DocumentRetryException.Reason.PERSISTENCE_FAILED, exception.getReason());
        assertSame(persistenceFailure, exception.getCause());
        InOrder order = inOrder(extractionMessageService, documentRepository);
        order.verify(extractionMessageService).publishExtract(document);
        order.verify(documentRepository).saveAndFlush(document);
        verifyNoMoreInteractions(extractionMessageService);
        verifyNoInteractions(minioClient);
        assertTrue(TransactionSynchronizationManager.getSynchronizations().isEmpty());
        log.info("Extraction retry persistence failure test completed successfully");
    }

    /** Verifies that a subsequent retry observes PROCESSING and cannot resend the same extraction request. */
    @Test
    void retryExtractionRejectsAnotherRetryAfterProcessingStarts() {
        log.info("Testing repeat extraction retry after successful dispatch");
        DocumentEntity document = retryDocument(DocumentState.UPLOADED);
        when(documentRepository.findVisibleDocumentForUpdate(document.getId(), 12L, false))
                .thenReturn(Optional.of(document));
        when(extractionMessageService.publishExtract(document)).thenReturn(true);
        when(documentRepository.saveAndFlush(document)).thenReturn(document);

        documentUploadService.retryExtraction(document.getId(), 12L, UserRole.EMPLOYEE);
        DocumentRetryException exception = assertThrows(DocumentRetryException.class,
                () -> documentUploadService.retryExtraction(document.getId(), 12L, UserRole.EMPLOYEE));

        assertEquals(DocumentRetryException.Reason.NOT_ALLOWED, exception.getReason());
        assertEquals(DocumentState.PROCESSING, document.getCurrentState());
        verify(extractionMessageService).publishExtract(document);
        verifyNoMoreInteractions(extractionMessageService);
        verify(documentRepository).saveAndFlush(document);
        verifyNoInteractions(minioClient);
        log.info("Repeat extraction retry test completed successfully");
    }

    /** Checks retry dispatch ordering, role scope, retained metadata, and the response after saving PROCESSING. */
    private void verifySuccessfulRetry(DocumentState initialState, UserRole role, boolean manager) {
        log.info("Verifying successful extraction retry. state={}, role={}", initialState, role);
        DocumentEntity document = retryDocument(initialState);
        document.setErrorCode("PREVIOUS_ERROR");
        document.setErrorMessage("Previous failure");
        Long originalOwnerId = manager ? 99L : 12L;
        document.setUploadedById(originalOwnerId);
        UUID originalId = document.getId();
        String originalKey = document.getObjectKey();
        OffsetDateTime originalUploadedAt = document.getUploadedAt();
        when(documentRepository.findVisibleDocumentForUpdate(originalId, 12L, manager))
                .thenReturn(Optional.of(document));
        when(extractionMessageService.publishExtract(document)).thenAnswer(invocation -> {
            assertEquals(initialState, document.getCurrentState());
            assertEquals(originalId, document.getId());
            assertEquals(originalKey, document.getObjectKey());
            return true;
        });
        when(documentRepository.saveAndFlush(document)).thenAnswer(invocation -> {
            assertEquals(DocumentState.PROCESSING, document.getCurrentState());
            return document;
        });

        DocumentUploadItemResponse response = documentUploadService.retryExtraction(originalId, 12L, role);

        assertEquals(originalId, response.getId());
        assertEquals("receipt.jpg", response.getOriginalFilename());
        assertEquals("image/jpeg", response.getMimeType());
        assertEquals(100L, response.getFileSize());
        assertEquals(UploadStatus.UPLOADED, response.getUploadStatus());
        assertEquals(DocumentState.PROCESSING, response.getCurrentState());
        assertNull(document.getErrorCode());
        assertNull(document.getErrorMessage());
        assertNull(response.getErrorCode());
        assertNull(response.getErrorMessage());
        assertEquals(originalUploadedAt, response.getUploadedAt());
        assertEquals(originalKey, document.getObjectKey());
        assertEquals("existing-checksum", document.getChecksumSha256());
        assertEquals(CommonStatus.ACTIVE, document.getStatus());
        assertEquals(originalOwnerId, document.getUploadedById());
        InOrder order = inOrder(documentRepository, extractionMessageService);
        order.verify(documentRepository).findVisibleDocumentForUpdate(originalId, 12L, manager);
        order.verify(extractionMessageService).publishExtract(document);
        order.verify(documentRepository).saveAndFlush(document);
        verifyNoMoreInteractions(documentRepository, extractionMessageService);
        verifyNoInteractions(minioClient);
        assertTrue(TransactionSynchronizationManager.getSynchronizations().isEmpty());
        log.info("Successful extraction retry verified. documentId={}", originalId);
    }

    /** Builds an existing, successfully stored document whose ID and storage metadata must survive a retry. */
    private DocumentEntity retryDocument(DocumentState state) {
        log.info("Creating existing document fixture for extraction retry. state={}", state);
        DocumentEntity document = DocumentEntity.builder()
                .id(UUID.randomUUID())
                .originalFilename("receipt.jpg")
                .mimeType("image/jpeg")
                .fileSize(100L)
                .objectKey("2026/10/existing-receipt.jpg")
                .checksumSha256("existing-checksum")
                .status(CommonStatus.ACTIVE)
                .uploadStatus(UploadStatus.UPLOADED)
                .currentState(state)
                .uploadedById(12L)
                .uploadedAt(OffsetDateTime.of(2026, 10, 1, 9, 0, 0, 0, ZoneOffset.UTC))
                .build();
        log.info("Existing extraction retry document fixture created. documentId={}", document.getId());
        return document;
    }
}
