package org.involens_be.service;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import lombok.extern.slf4j.Slf4j;
import org.involens_be.common.CommonStatus;
import org.involens_be.common.DocumentState;
import org.involens_be.common.UploadStatus;
import org.involens_be.common.UserRole;
import org.involens_be.dto.request.DocumentListRequest;
import org.involens_be.entity.DocumentEntity;
import org.involens_be.entity.UserEntity;
import org.involens_be.exception.DocumentNotFoundException;
import org.involens_be.exception.InvalidDocumentFilterException;
import org.involens_be.repository.DocumentRepository;
import org.involens_be.repository.ExtractionResultRepository;
import org.involens_be.repository.UserRepository;
import org.involens_be.mapper.DocumentMapper;
import org.mapstruct.factory.Mappers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Slf4j
@ExtendWith(MockitoExtension.class)
class DocumentQueryServiceTest {
    @Mock private DocumentRepository documents;
    @Mock private UserRepository users;
    @Mock private ExtractionResultRepository extractionResults;
    private DocumentService service;

    /** Generates real local signatures for images and PDFs with an hour-long expiry without network calls. */
    @Test
    void detailSignsBrowserAccessibleImageAndPdfUrls() {
        log.info("Testing locally signed image and PDF URLs");
        var signer = new org.involens_be.config.AppConfig().publicMinioClient(
                "http://localhost:9000", "test-access", "test-secret", "us-east-1");
        org.springframework.test.util.ReflectionTestUtils.setField(service, "publicMinioClient", signer);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "bucket", "involens-documents");
        for (String mime : java.util.List.of("image/png", "application/pdf")) {
            UUID id = UUID.randomUUID();
            var document = DocumentEntity.builder().id(id).uploadedById(12L).status(CommonStatus.ACTIVE)
                    .mimeType(mime).uploadStatus(UploadStatus.UPLOADED).objectKey("documents/" + id).build();
            when(documents.findByIdAndUploadedByIdAndStatus(id, 12L, CommonStatus.ACTIVE)).thenReturn(Optional.of(document));
            var response = service.getDocument(id, 12L, UserRole.EMPLOYEE);
            assertTrue(response.getFileUrl().startsWith("http://localhost:9000/involens-documents/documents/" + id + "?"));
            assertTrue(response.getFileUrl().contains("X-Amz-Expires=3600"));
            assertTrue(response.getFileUrl().contains("X-Amz-Signature="));
        }
    }

    /** Returns stored document errors, including null values after errors have been cleared. */
    @Test
    void detailReturnsSharedDocumentErrors() {
        log.info("Testing shared document error details");
        UUID id = UUID.randomUUID();
        var document = DocumentEntity.builder().id(id).uploadedById(12L).status(CommonStatus.ACTIVE)
                .uploadStatus(UploadStatus.FAILED).currentState(DocumentState.UPLOADED_FAILED)
                .errorCode("FILE_TOO_LARGE").errorMessage("File exceeds 10MB").build();
        when(documents.findByIdAndUploadedByIdAndStatus(id, 12L, CommonStatus.ACTIVE))
                .thenReturn(Optional.of(document));
        var response = service.getDocument(id, 12L, UserRole.EMPLOYEE);
        assertEquals("FILE_TOO_LARGE", response.getErrorCode());
        assertEquals("File exceeds 10MB", response.getErrorMessage());
        document.setCurrentState(DocumentState.PENDING_FAILURE);
        document.setErrorCode("EXTRACTION_DISPATCH_FAILED");
        document.setErrorMessage("Không thể gửi tài liệu sang AI trích xuất");
        response = service.getDocument(id, 12L, UserRole.EMPLOYEE);
        assertEquals("EXTRACTION_DISPATCH_FAILED", response.getErrorCode());
        assertEquals("Không thể gửi tài liệu sang AI trích xuất", response.getErrorMessage());
        document.setErrorCode(null);
        document.setErrorMessage(null);
        response = service.getDocument(id, 12L, UserRole.EMPLOYEE);
        assertNull(response.getErrorCode());
        assertNull(response.getErrorMessage());
        log.info("Shared document error details verified");
    }

    /** Returns no URL for failed uploads and missing stored-file keys without calling MinIO. */
    @Test
    void detailWithoutStoredFileReturnsNullUrl() {
        log.info("Testing missing document file URLs");
        UUID id = UUID.randomUUID();
        var document = DocumentEntity.builder().id(id).uploadedById(12L).status(CommonStatus.ACTIVE)
                .uploadStatus(UploadStatus.UPLOADED).build();
        when(documents.findByIdAndUploadedByIdAndStatus(id, 12L, CommonStatus.ACTIVE)).thenReturn(Optional.of(document));
        assertNull(service.getDocument(id, 12L, UserRole.EMPLOYEE).getFileUrl());
        document.setObjectKey(" ");
        assertNull(service.getDocument(id, 12L, UserRole.EMPLOYEE).getFileUrl());
        document.setObjectKey("stored-file");
        document.setUploadStatus(null);
        assertNull(service.getDocument(id, 12L, UserRole.EMPLOYEE).getFileUrl());
    }

    /** Rejects signature failures with an explicit server error instead of silently omitting the URL. */
    @Test
    void detailSigningFailureReturnsServerError() throws Exception {
        log.info("Testing document URL signing failure");
        var signer = mock(io.minio.MinioClient.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "publicMinioClient", signer);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "bucket", "involens-documents");
        when(signer.getPresignedObjectUrl(any())).thenThrow(new java.io.IOException("signing failure"));
        UUID id = UUID.randomUUID();
        var document = DocumentEntity.builder().id(id).uploadedById(12L).status(CommonStatus.ACTIVE)
                .uploadStatus(UploadStatus.UPLOADED).objectKey("stored-file").build();
        when(documents.findByIdAndUploadedByIdAndStatus(id, 12L, CommonStatus.ACTIVE)).thenReturn(Optional.of(document));
        var exception = assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> service.getDocument(id, 12L, UserRole.EMPLOYEE));
        assertEquals(500, exception.getStatusCode().value());
    }

    /** Allows employees to deactivate their own documents in every state without changing associated data. */
    @Test
    void employeeDeactivationPreservesEveryProcessingState() {
        log.info("Testing employee document deactivation across all processing states");
        for (DocumentState state : DocumentState.values()) {
            UUID id = UUID.randomUUID();
            DocumentEntity document = DocumentEntity.builder().id(id).uploadedById(12L)
                    .status(CommonStatus.ACTIVE).currentState(state).objectKey("stored-file")
                    .uploadStatus(UploadStatus.UPLOADED).build();
            when(documents.findByIdAndUploadedByIdAndStatus(id, 12L, CommonStatus.ACTIVE))
                    .thenReturn(Optional.of(document));

            service.deactivateDocument(id, 12L, UserRole.EMPLOYEE);

            assertEquals(CommonStatus.INACTIVE, document.getStatus());
            assertEquals(state, document.getCurrentState());
            assertEquals("stored-file", document.getObjectKey());
            assertEquals(UploadStatus.UPLOADED, document.getUploadStatus());
            verify(documents).save(document);
        }
        verify(documents, never()).findByIdAndStatus(any(), any());
        verify(documents, never()).delete(any(DocumentEntity.class));
        verifyNoInteractions(users, extractionResults);
    }

    /** Allows managers to deactivate another uploader's active document using global visibility. */
    @Test
    void managerDeactivatesAnotherUploadersDocument() {
        log.info("Testing manager document deactivation across uploaders");
        UUID id = UUID.randomUUID();
        DocumentEntity document = DocumentEntity.builder().id(id).uploadedById(99L)
                .status(CommonStatus.ACTIVE).currentState(DocumentState.PROCESSING).build();
        when(documents.findByIdAndStatus(id, CommonStatus.ACTIVE)).thenReturn(Optional.of(document));

        service.deactivateDocument(id, 12L, UserRole.MANAGER);

        assertEquals(CommonStatus.INACTIVE, document.getStatus());
        assertEquals(DocumentState.PROCESSING, document.getCurrentState());
        verify(documents).save(document);
        verify(documents, never()).findByIdAndUploadedByIdAndStatus(any(), any(), any());
        verifyNoInteractions(users, extractionResults);
    }

    /** Rejects inaccessible or inactive document lookups without saving or deleting data. */
    @Test
    void deactivationRejectsDocumentsOutsideActiveVisibility() {
        log.info("Testing not-found document deactivation for both roles");
        UUID id = UUID.randomUUID();
        when(documents.findByIdAndUploadedByIdAndStatus(id, 12L, CommonStatus.ACTIVE))
                .thenReturn(Optional.empty());
        when(documents.findByIdAndStatus(id, CommonStatus.ACTIVE)).thenReturn(Optional.empty());

        assertThrows(DocumentNotFoundException.class, () -> service.deactivateDocument(id, 12L, UserRole.EMPLOYEE));
        assertThrows(DocumentNotFoundException.class, () -> service.deactivateDocument(id, 12L, UserRole.MANAGER));

        verify(documents, never()).save(any());
        verify(documents, never()).delete(any(DocumentEntity.class));
        verifyNoInteractions(users, extractionResults);
    }

    /** Rejects a repeated deactivation once the document no longer matches the active lookup. */
    @Test
    void repeatedDeactivationReturnsNotFound() {
        log.info("Testing repeated document deactivation");
        UUID id = UUID.randomUUID();
        DocumentEntity document = DocumentEntity.builder().id(id).uploadedById(12L)
                .status(CommonStatus.ACTIVE).currentState(DocumentState.EXTRACT_SUCCESS).build();
        when(documents.findByIdAndUploadedByIdAndStatus(id, 12L, CommonStatus.ACTIVE))
                .thenReturn(Optional.of(document), Optional.empty());
        service.deactivateDocument(id, 12L, UserRole.EMPLOYEE);
        assertThrows(DocumentNotFoundException.class, () -> service.deactivateDocument(id, 12L, UserRole.EMPLOYEE));
        verify(documents, times(1)).save(document);
    }

    /** Verifies all state mappings while uploaded documents contribute only to the total. */
    @Test
    void summaryCountsStatesAndIncludesSevenUploadDays() {
        log.info("Testing dashboard state mapping and daily upload counts");
        when(documents.countByState(CommonStatus.ACTIVE, false, 12L)).thenReturn(java.util.List.of(
                new Object[]{DocumentState.UPLOADED, 2L},
                new Object[]{DocumentState.PROCESSING, 3L},
                new Object[]{DocumentState.REVIEW_REQUIRED, 4L},
                new Object[]{DocumentState.EXTRACT_SUCCESS, 5L},
                new Object[]{DocumentState.UPLOADED_FAILED, 6L},
                new Object[]{DocumentState.EXTRACT_FAILED, 7L}));
        when(documents.count(any(Specification.class))).thenReturn(0L, 1L, 2L, 3L, 4L, 5L, 6L);
        var result = service.getSummary(12L, UserRole.EMPLOYEE);
        assertEquals(27L, result.getTotal());
        assertEquals(3L, result.getProcessing());
        assertEquals(4L, result.getReviewRequired());
        assertEquals(5L, result.getSuccess());
        assertEquals(6L, result.getRejected());
        assertEquals(7L, result.getFailed());
        assertEquals(7, result.getDailyUploads().size());
        var today = LocalDate.now(java.time.ZoneId.of("Asia/Ho_Chi_Minh"));
        for (int i = 0; i < 7; i++) {
            assertEquals(today.minusDays(6 - i), result.getDailyUploads().get(i).getDate());
            assertEquals(i, result.getDailyUploads().get(i).getCount());
        }
    }

    /** Verifies empty summaries and role restrictions with inclusive midnight and exclusive next midnight. */
    @Test
    @SuppressWarnings("unchecked")
    void summaryPreservesVisibilityAndVietnamDayBounds() {
        log.info("Testing empty dashboard, visibility and Vietnam calendar boundaries");
        for (UserRole role : UserRole.values()) {
            reset(documents);
            when(documents.countByState(CommonStatus.ACTIVE, role == UserRole.MANAGER, 12L))
                    .thenReturn(java.util.List.of());
            var result = service.getSummary(12L, role);
            assertEquals(0L, result.getTotal());
            assertEquals(0L, result.getProcessing());
            assertEquals(0L, result.getReviewRequired());
            assertEquals(0L, result.getSuccess());
            assertEquals(0L, result.getRejected());
            assertEquals(0L, result.getFailed());
            assertTrue(result.getDailyUploads().stream().allMatch(day -> day.getCount() == 0));
            ArgumentCaptor<Specification<DocumentEntity>> captor = ArgumentCaptor.forClass(Specification.class);
            verify(documents, times(7)).count(captor.capture());
            Root<DocumentEntity> root = mock(Root.class);
            CriteriaBuilder builder = mock(CriteriaBuilder.class);
            Path<Object> owner = mock(Path.class);
            Path<Object> status = mock(Path.class);
            Path<OffsetDateTime> timestamp = mock(Path.class);
            when(root.get("status")).thenReturn(status);
            if (role == UserRole.EMPLOYEE) {
                when(root.get("uploadedById")).thenReturn(owner);
            }
            when(root.<OffsetDateTime>get("uploadedAt")).thenReturn(timestamp);
            captor.getAllValues().getFirst().toPredicate(root, null, builder);
            verify(builder).equal(status, CommonStatus.ACTIVE);
            if (role == UserRole.EMPLOYEE) {
                verify(builder).equal(owner, 12L);
            } else {
                verify(root, never()).get("uploadedById");
            }
            var firstDate = result.getDailyUploads().getFirst().getDate();
            verify(builder).greaterThanOrEqualTo(timestamp,
                    firstDate.atStartOfDay(java.time.ZoneId.of("Asia/Ho_Chi_Minh")).toOffsetDateTime());
            verify(builder).lessThan(timestamp,
                    firstDate.plusDays(1).atStartOfDay(java.time.ZoneId.of("Asia/Ho_Chi_Minh")).toOffsetDateTime());
        }
    }

    /** Creates an isolated document query service without database or storage connections. */
    @BeforeEach
    void setUp() {
        log.info("Preparing document query tests");
        service = new DocumentService(documents, mock(DocumentStatusNotificationService.class), users,
                Mappers.getMapper(DocumentMapper.class), extractionResults,
                mock(org.involens_be.repository.InvoiceRepository.class),
                mock(org.involens_be.repository.ReviewTaskRepository.class),
                mock(org.involens_be.repository.InvoiceItemRepository.class));
        log.info("Document query test setup completed");
    }

    /** Keeps owner, state, date and filename restrictions while matching a keyword against filename or uploader. */
    @Test
    @SuppressWarnings("unchecked")
    void listAppliesOwnerStatusDateAndFilenameFilters() {
        log.info("Testing mandatory document scope and optional filters");
        var request = new DocumentListRequest();
        request.setUploaded_by(99L);
        request.setDate_from(LocalDate.parse("2026-09-10"));
        request.setDate_to(LocalDate.parse("2026-09-10"));
        request.setQ("Bill_10%");
        request.setFilename("Receipt");
        request.setCurrent_state(DocumentState.PROCESSING);
        when(documents.findAll(any(Specification.class), any(Pageable.class))).thenReturn(Page.empty());

        var response = service.listDocuments(request, 12L, UserRole.EMPLOYEE);
        ArgumentCaptor<Specification<DocumentEntity>> specification = ArgumentCaptor.forClass(Specification.class);
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(documents).findAll(specification.capture(), pageable.capture());
        assertEquals(8, response.getPageSize());
        assertEquals(1, response.getPage());
        assertEquals(0, response.getTotal());
        assertTrue(response.getItems().isEmpty());
        assertEquals(0, pageable.getValue().getPageNumber());
        assertEquals(8, pageable.getValue().getPageSize());
        assertEquals("uploadedAt: DESC,id: DESC", pageable.getValue().getSort().toString().replace(", ", ","));

        Root<DocumentEntity> root = mock(Root.class);
        CriteriaBuilder builder = mock(CriteriaBuilder.class);
        Path<Object> owner = mock(Path.class);
        Path<Object> status = mock(Path.class);
        Path<Object> state = mock(Path.class);
        Path<OffsetDateTime> timestamp = mock(Path.class);
        Path<String> filename = mock(Path.class);
        CriteriaQuery<DocumentEntity> query = mock(CriteriaQuery.class);
        Subquery<Long> uploaderMatch = mock(Subquery.class);
        Root<UserEntity> uploader = mock(Root.class);
        Path<Long> uploaderId = mock(Path.class);
        Path<String> uploaderName = mock(Path.class);
        Predicate filenameMatch = mock(Predicate.class);
        Predicate uploaderExists = mock(Predicate.class);
        Predicate keywordMatch = mock(Predicate.class);
        Predicate filenameFilter = mock(Predicate.class);
        Predicate uploaderNameMatch = mock(Predicate.class);
        Predicate uploaderCorrelation = mock(Predicate.class);
        Predicate activeFilter = mock(Predicate.class);
        Predicate ownerFilter = mock(Predicate.class);
        Predicate stateFilter = mock(Predicate.class);
        when(query.subquery(Long.class)).thenReturn(uploaderMatch);
        when(uploaderMatch.from(UserEntity.class)).thenReturn(uploader);
        when(uploader.<Long>get("id")).thenReturn(uploaderId);
        when(uploader.<String>get("fullName")).thenReturn(uploaderName);
        when(uploaderMatch.select(uploaderId)).thenReturn(uploaderMatch);
        when(builder.lower(uploaderName)).thenReturn(uploaderName);
        when(builder.like(uploaderName, "%bill!_10!%%", '!')).thenReturn(uploaderNameMatch);
        doReturn(activeFilter).when(builder).equal(status, CommonStatus.ACTIVE);
        doReturn(ownerFilter).when(builder).equal(owner, 12L);
        doReturn(stateFilter).when(builder).equal(state, DocumentState.PROCESSING);
        doReturn(uploaderCorrelation).when(builder).equal(uploaderId, owner);
        when(builder.like(filename, "%bill!_10!%%", '!')).thenReturn(filenameMatch);
        when(builder.like(filename, "%receipt%", '!')).thenReturn(filenameFilter);
        when(builder.exists(uploaderMatch)).thenReturn(uploaderExists);
        when(builder.or(filenameMatch, uploaderExists)).thenReturn(keywordMatch);
        when(root.get("uploadedById")).thenReturn(owner);
        when(root.get("status")).thenReturn(status);
        when(root.get("currentState")).thenReturn(state);
        when(root.<OffsetDateTime>get("uploadedAt")).thenReturn(timestamp);
        when(root.<String>get("originalFilename")).thenReturn(filename);
        when(builder.lower(filename)).thenReturn(filename);
        specification.getValue().toPredicate(root, query, builder);

        verify(builder).equal(owner, 12L);
        verify(builder, never()).equal(owner, 99L);
        verify(builder).equal(status, CommonStatus.ACTIVE);
        verify(builder).equal(state, DocumentState.PROCESSING);
        verify(builder).greaterThanOrEqualTo(timestamp, OffsetDateTime.parse("2026-09-10T00:00:00+07:00"));
        verify(builder).lessThan(timestamp, OffsetDateTime.parse("2026-09-11T00:00:00+07:00"));
        verify(builder).like(filename, "%bill!_10!%%", '!');
        verify(builder).like(filename, "%receipt%", '!');
        verify(builder).like(uploaderName, "%bill!_10!%%", '!');
        verify(builder).equal(uploaderId, owner);
        verify(uploaderMatch).where(uploaderCorrelation, uploaderNameMatch);
        verify(builder).or(filenameMatch, uploaderExists);
        ArgumentCaptor<Predicate[]> combinedFilters = ArgumentCaptor.forClass(Predicate[].class);
        verify(builder).and(combinedFilters.capture());
        assertTrue(java.util.Arrays.asList(combinedFilters.getValue()).contains(keywordMatch));
        assertTrue(java.util.Arrays.asList(combinedFilters.getValue()).contains(filenameFilter));
        assertTrue(java.util.Arrays.asList(combinedFilters.getValue()).contains(activeFilter));
        assertTrue(java.util.Arrays.asList(combinedFilters.getValue()).contains(ownerFilter));
        assertTrue(java.util.Arrays.asList(combinedFilters.getValue()).contains(stateFilter));
        verifyNoInteractions(users);
    }

    /** Skips uploader keyword lookups for blank searches and retains the manager's explicit uploader filter. */
    @Test
    @SuppressWarnings("unchecked")
    void blankKeywordPreservesManagerUploaderFilter() {
        log.info("Testing blank document keywords with manager uploader filtering");
        when(documents.findAll(any(Specification.class), any(Pageable.class))).thenReturn(Page.empty());
        for (String keyword : new String[]{null, "", "  "}) {
            var request = new DocumentListRequest();
            request.setQ(keyword);
            request.setUploaded_by(99L);
            service.listDocuments(request, 12L, UserRole.MANAGER);
        }
        ArgumentCaptor<Specification<DocumentEntity>> specifications = ArgumentCaptor.forClass(Specification.class);
        verify(documents, times(3)).findAll(specifications.capture(), any(Pageable.class));
        Root<DocumentEntity> root = mock(Root.class);
        CriteriaBuilder builder = mock(CriteriaBuilder.class);
        CriteriaQuery<DocumentEntity> query = mock(CriteriaQuery.class);
        Path<Object> owner = mock(Path.class);
        Path<Object> status = mock(Path.class);
        when(root.get("uploadedById")).thenReturn(owner);
        when(root.get("status")).thenReturn(status);
        for (var specification : specifications.getAllValues()) {
            specification.toPredicate(root, query, builder);
        }
        verify(builder, times(3)).equal(owner, 99L);
        verify(builder, times(3)).equal(status, CommonStatus.ACTIVE);
        verify(builder, never()).equal(owner, 12L);
        verifyNoInteractions(query, users);
        log.info("Blank document keywords preserve manager uploader filtering");
    }

    /** Rejects invalid dates and paging before any document lookup can run. */
    @Test
    void rejectsInvalidFilters() {
        log.info("Testing document filter validation");
        var request = new DocumentListRequest();
        request.setDate_from(LocalDate.parse("2026-09-11"));
        request.setDate_to(LocalDate.parse("2026-09-10"));
        assertThrows(InvalidDocumentFilterException.class,
                () -> service.listDocuments(request, 12L, UserRole.EMPLOYEE));
        request.setDate_from(null);
        request.setPage(0);
        assertThrows(InvalidDocumentFilterException.class,
                () -> service.listDocuments(request, 12L, UserRole.EMPLOYEE));
        request.setPage(1);
        request.setPage_size(101);
        assertThrows(InvalidDocumentFilterException.class,
                () -> service.listDocuments(request, 12L, UserRole.EMPLOYEE));
        verifyNoInteractions(documents, users);
    }

    /** Ensures detail lookup uses owner and active constraints and returns not-found when nothing is visible. */
    @Test
    void inaccessibleDetailIsNotFound() {
        log.info("Testing inaccessible document detail");
        UUID id = UUID.randomUUID();
        when(documents.findByIdAndUploadedByIdAndStatus(id, 12L, CommonStatus.ACTIVE)).thenReturn(Optional.empty());
        assertThrows(DocumentNotFoundException.class,
                () -> service.getDocument(id, 12L, UserRole.EMPLOYEE));
        verifyNoInteractions(users);
    }

    /** Preserves a missing uploader's ID, returns a null name, and normalizes detail timestamps to UTC. */
    @Test
    void detailReturnsUtcAndMissingUploaderName() {
        log.info("Testing document detail response mapping");
        UUID id = UUID.randomUUID();
        var document = DocumentEntity.builder().id(id).uploadedById(12L).originalFilename("receipt.pdf")
                .mimeType("application/pdf").fileSize(100L).status(CommonStatus.ACTIVE)
                .uploadStatus(UploadStatus.UPLOADED).currentState(DocumentState.UPLOADED)
                .uploadedAt(OffsetDateTime.parse("2026-09-10T08:15:00+07:00")).build();
        when(documents.findByIdAndUploadedByIdAndStatus(id, 12L, CommonStatus.ACTIVE)).thenReturn(Optional.of(document));
        when(users.findById(12L)).thenReturn(Optional.empty());
        var response = service.getDocument(id, 12L, UserRole.EMPLOYEE);
        assertEquals(id, response.getId());
        assertEquals(12L, response.getUploadedBy().getId());
        assertNull(response.getUploadedBy().getFullName());
        assertEquals(OffsetDateTime.parse("2026-09-10T01:15:00Z"), response.getUploadedAt());
    }

    /** Allows a manager to load another uploader's active document and resolves that uploader's name. */
    @Test
    void managerCanLoadAnyActiveDocument() {
        UUID id = UUID.randomUUID();
        var document = DocumentEntity.builder().id(id).uploadedById(44L).originalFilename("invoice.pdf")
                .mimeType("application/pdf").fileSize(100L).status(CommonStatus.ACTIVE)
                .uploadStatus(UploadStatus.UPLOADED).currentState(DocumentState.REVIEW_REQUIRED).build();
        when(documents.findByIdAndStatus(id, CommonStatus.ACTIVE)).thenReturn(Optional.of(document));
        when(users.findById(44L)).thenReturn(Optional.of(
                org.involens_be.entity.UserEntity.builder().id(44L).fullName("Other User").build()));

        var response = service.getDocument(id, 12L, UserRole.MANAGER);

        assertEquals(44L, response.getUploadedBy().getId());
        assertEquals("Other User", response.getUploadedBy().getFullName());
        verify(documents, never()).findByIdAndUploadedByIdAndStatus(any(), anyLong(), any());
    }
}
