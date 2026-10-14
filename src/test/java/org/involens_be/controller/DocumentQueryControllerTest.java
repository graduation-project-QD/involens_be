package org.involens_be.controller;

import lombok.extern.slf4j.Slf4j;
import org.involens_be.common.DocumentState;
import org.involens_be.common.UploadStatus;
import org.involens_be.common.UserRole;
import org.involens_be.dto.request.DocumentListRequest;
import org.involens_be.dto.response.DocumentListResponse;
import org.involens_be.dto.response.DocumentUploadItemResponse;
import org.involens_be.exception.DocumentNotFoundException;
import org.involens_be.exception.DocumentRetryException;
import org.involens_be.exception.ExtractionResultNotFoundException;
import org.involens_be.exception.GlobalExceptionHandler;
import org.involens_be.service.DocumentService;
import org.involens_be.service.DocumentUploadService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.UnexpectedRollbackException;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Slf4j
class DocumentQueryControllerTest {
    private DocumentService queries;
    private DocumentUploadService uploads;
    private MockMvc mvc;

    /** Exposes the signed file URL and shared document errors under the API's snake-case fields. */
    @Test
    void detailReturnsFileUrl() throws Exception {
        log.info("Testing document file URL JSON contract");
        UUID id = UUID.randomUUID();
        when(queries.getDocument(id, 12L, org.involens_be.common.UserRole.EMPLOYEE))
                .thenReturn(org.involens_be.dto.response.DocumentDetailResponse.builder().id(id)
                        .fileUrl("http://localhost:9000/signed-file")
                        .errorCode("EXTRACTION_DISPATCH_FAILED").errorMessage("Không thể gửi tài liệu sang AI trích xuất").build());
        mvc.perform(get("/api/v1/documents/{documentId}", id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.file_url").value("http://localhost:9000/signed-file"))
                .andExpect(jsonPath("$.data.error_code").value("EXTRACTION_DISPATCH_FAILED"))
                .andExpect(jsonPath("$.data.error_message").value("Không thể gửi tài liệu sang AI trích xuất"));
    }

    /** Returns HTTP 500 when the detail service cannot sign a stored document URL. */
    @Test
    void detailUrlFailureReturnsServerError() throws Exception {
        log.info("Testing document URL failure HTTP response");
        UUID id = UUID.randomUUID();
        when(queries.getDocument(id, 12L, org.involens_be.common.UserRole.EMPLOYEE))
                .thenThrow(new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR, "URL creation failed"));
        mvc.perform(get("/api/v1/documents/{documentId}", id)).andExpect(status().isInternalServerError());
    }

    /** Returns the existing success envelope and forwards authenticated ownership for deactivation. */
    @Test
    void deleteDeactivatesDocumentWithAuthenticatedScope() throws Exception {
        log.info("Testing document deactivation success response");
        UUID id = UUID.randomUUID();
        mvc.perform(delete("/api/v1/documents/{documentId}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(0));
        verify(queries).deactivateDocument(id, 12L, org.involens_be.common.UserRole.EMPLOYEE);
    }

    /** Returns the existing not-found error for documents unavailable to the caller. */
    @Test
    void deleteUnavailableDocumentReturnsNotFound() throws Exception {
        log.info("Testing unavailable document deactivation response");
        UUID id = UUID.randomUUID();
        doThrow(new DocumentNotFoundException()).when(queries)
                .deactivateDocument(id, 12L, org.involens_be.common.UserRole.EMPLOYEE);
        mvc.perform(delete("/api/v1/documents/{documentId}", id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("DOCUMENT_NOT_FOUND"));
    }

    /** Rejects malformed document identifiers before business deactivation runs. */
    @Test
    void deleteInvalidUuidReturnsBadRequest() throws Exception {
        log.info("Testing invalid document deactivation identifier");
        mvc.perform(delete("/api/v1/documents/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        verifyNoInteractions(queries);
    }

    /** Verifies the summary route takes precedence over document IDs and preserves the response contract. */
    @Test
    void summaryReturnsDashboardEnvelope() throws Exception {
        log.info("Testing dashboard summary HTTP response");
        when(queries.getSummary(12L, org.involens_be.common.UserRole.EMPLOYEE))
                .thenReturn(org.involens_be.dto.response.DocumentSummaryResponse.builder()
                        .total(10).processing(1).reviewRequired(2).success(3).rejected(1).failed(3)
                        .dailyUploads(List.of(org.involens_be.dto.response.DocumentSummaryResponse.DailyUploads
                                .builder().date(java.time.LocalDate.of(2026, 9, 21)).count(2).build())).build());
        mvc.perform(get("/api/v1/documents/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(0))
                .andExpect(jsonPath("$.data.total").value(10))
                .andExpect(jsonPath("$.data.processing").value(1))
                .andExpect(jsonPath("$.data.review_required").value(2))
                .andExpect(jsonPath("$.data.success").value(3))
                .andExpect(jsonPath("$.data.rejected").value(1))
                .andExpect(jsonPath("$.data.failed").value(3))
                .andExpect(jsonPath("$.data.daily_uploads[0].date").value("2026-09-21"))
                .andExpect(jsonPath("$.data.daily_uploads[0].count").value(2));
        verify(queries).getSummary(12L, org.involens_be.common.UserRole.EMPLOYEE);
    }

    /** Configures MVC binding and serialization with isolated services and a test JWT principal. */
    @BeforeEach
    void setUp() {
        log.info("Preparing document query HTTP contract tests");
        queries = mock(DocumentService.class);
        uploads = mock(DocumentUploadService.class);
        mvc = MockMvcBuilders.standaloneSetup(new DocumentController(queries, uploads))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build();
        var jwt = Jwt.withTokenValue("test").header("alg", "HS256").subject("test@example.com")
                .claim("userId", 12L).claim("role", "EMPLOYEE").build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(jwt, null, List.of()));
        log.info("Document query HTTP contract test setup completed");
    }

    /** Retries without a request body and returns the original document ID in PROCESSING state. */
    @Test
    void retryExtractionReturnsProcessingDocumentWithEmployeeScope() throws Exception {
        log.info("Testing document extraction retry success and employee scope");
        UUID id = UUID.randomUUID();
        when(uploads.retryExtraction(id, 12L, UserRole.EMPLOYEE))
                .thenReturn(DocumentUploadItemResponse.builder().id(id).originalFilename("invoice.png")
                        .uploadStatus(UploadStatus.UPLOADED).currentState(DocumentState.PROCESSING).build());

        mvc.perform(post("/api/v1/documents/{documentId}/retry-extraction", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(0))
                .andExpect(jsonPath("$.data.id").value(id.toString()))
                .andExpect(jsonPath("$.data.original_filename").value("invoice.png"))
                .andExpect(jsonPath("$.data.upload_status").value("UPLOADED"))
                .andExpect(jsonPath("$.data.current_state").value("PROCESSING"));
        verify(uploads).retryExtraction(id, 12L, UserRole.EMPLOYEE);
        verifyNoInteractions(queries);
    }

    /** Forwards the manager's JWT identity so the retry service can apply manager document scope. */
    @Test
    void retryExtractionForwardsManagerScope() throws Exception {
        log.info("Testing manager document extraction retry scope");
        UUID id = UUID.randomUUID();
        Jwt jwt = Jwt.withTokenValue("manager-test").header("alg", "HS256").subject("manager@example.com")
                .claim("userId", 34L).claim("role", "MANAGER").build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(jwt, null, List.of()));
        when(uploads.retryExtraction(id, 34L, UserRole.MANAGER))
                .thenReturn(DocumentUploadItemResponse.builder().id(id).currentState(DocumentState.PROCESSING).build());

        mvc.perform(post("/api/v1/documents/{documentId}/retry-extraction", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(id.toString()))
                .andExpect(jsonPath("$.data.current_state").value("PROCESSING"));
        verify(uploads).retryExtraction(id, 34L, UserRole.MANAGER);
    }

    /** Hides missing, inactive, or inaccessible documents behind the existing not-found envelope. */
    @Test
    void retryExtractionUnavailableDocumentReturnsNotFound() throws Exception {
        log.info("Testing extraction retry for an unavailable document");
        UUID id = UUID.randomUUID();
        when(uploads.retryExtraction(id, 12L, UserRole.EMPLOYEE)).thenThrow(new DocumentNotFoundException());

        mvc.perform(post("/api/v1/documents/{documentId}/retry-extraction", id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(1))
                .andExpect(jsonPath("$.error.code").value("DOCUMENT_NOT_FOUND"));
    }

    /** Returns HTTP 409 when the document state or stored file makes retry ineligible. */
    @Test
    void retryExtractionNotAllowedReturnsConflict() throws Exception {
        log.info("Testing ineligible extraction retry HTTP response");
        UUID id = UUID.randomUUID();
        when(uploads.retryExtraction(id, 12L, UserRole.EMPLOYEE))
                .thenThrow(new DocumentRetryException(DocumentRetryException.Reason.NOT_ALLOWED,
                        "Document is not eligible for extraction retry"));

        mvc.perform(post("/api/v1/documents/{documentId}/retry-extraction", id))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(1))
                .andExpect(jsonPath("$.error.code").value("DOCUMENT_RETRY_NOT_ALLOWED"));
    }

    /** Returns HTTP 503 when the stored document cannot be dispatched to RabbitMQ. */
    @Test
    void retryExtractionDispatchFailureReturnsUnavailable() throws Exception {
        log.info("Testing extraction retry dispatch failure HTTP response");
        UUID id = UUID.randomUUID();
        when(uploads.retryExtraction(id, 12L, UserRole.EMPLOYEE))
                .thenThrow(new DocumentRetryException(DocumentRetryException.Reason.DISPATCH_FAILED,
                        "Extraction dispatch failed"));

        mvc.perform(post("/api/v1/documents/{documentId}/retry-extraction", id))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value(1))
                .andExpect(jsonPath("$.error.code").value("DOCUMENT_RETRY_DISPATCH_FAILED"));
    }

    /** Returns HTTP 500 when the retry service cannot persist PROCESSING after dispatch. */
    @Test
    void retryExtractionPersistenceFailureReturnsServerError() throws Exception {
        log.info("Testing extraction retry persistence failure HTTP response");
        UUID id = UUID.randomUUID();
        when(uploads.retryExtraction(id, 12L, UserRole.EMPLOYEE))
                .thenThrow(new DocumentRetryException(DocumentRetryException.Reason.PERSISTENCE_FAILED,
                        "Document extraction retry could not be persisted"));

        mvc.perform(post("/api/v1/documents/{documentId}/retry-extraction", id))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(1))
                .andExpect(jsonPath("$.error.code").value("DOCUMENT_RETRY_FAILED"));
    }

    /** Converts transaction proxy failures to the retry error envelope without repeating dispatch. */
    @Test
    void retryExtractionTransactionFailureReturnsServerErrorWithoutRetrying() throws Exception {
        log.info("Testing extraction retry transaction completion failure HTTP response");
        UUID id = UUID.randomUUID();
        when(uploads.retryExtraction(id, 12L, UserRole.EMPLOYEE))
                .thenThrow(new UnexpectedRollbackException("Retry transaction rolled back"));

        mvc.perform(post("/api/v1/documents/{documentId}/retry-extraction", id))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(1))
                .andExpect(jsonPath("$.error.code").value("DOCUMENT_RETRY_FAILED"));
        verify(uploads, times(1)).retryExtraction(id, 12L, UserRole.EMPLOYEE);
        verifyNoMoreInteractions(uploads);
    }

    /** Rejects a malformed document identifier before calling either document service. */
    @Test
    void retryExtractionInvalidUuidReturnsBadRequest() throws Exception {
        log.info("Testing malformed extraction retry document identifier");
        mvc.perform(post("/api/v1/documents/not-a-uuid/retry-extraction"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(1))
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        verifyNoInteractions(uploads, queries);
    }

    /** Clears the isolated principal so no authentication state leaks to another test. */
    @org.junit.jupiter.api.AfterEach
    void cleanUp() {
        log.info("Clearing test security context");
        SecurityContextHolder.clearContext();
    }

    /** Verifies empty filter binding and the snake-case, one-based pagination response contract. */
    @Test
    void emptyFiltersUseDefaultPageSize() throws Exception {
        log.info("Testing empty document filters over HTTP");
        when(queries.listDocuments(any(), eq(12L), eq(org.involens_be.common.UserRole.EMPLOYEE)))
                .thenAnswer(invocation -> {
            DocumentListRequest request = invocation.getArgument(0);
            assertNull(request.getCurrent_state());
            assertNull(request.getUploaded_by());
            assertNull(request.getDate_from());
            assertNull(request.getDate_to());
            return DocumentListResponse.builder().items(List.of()).total(0)
                    .page(request.getPage()).pageSize(request.getPage_size()).build();
        });
        mvc.perform(get("/api/v1/documents").param("current_state", "").param("uploaded_by", "")
                        .param("date_from", "").param("date_to", "").param("q", "").param("filename", ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(0))
                .andExpect(jsonPath("$.data.items").isArray())
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.page_size").value(8))
                .andExpect(jsonPath("$.data.total").value(0));
    }

    /** Verifies malformed filter values, page bounds, and UUIDs are rejected as HTTP 400. */
    @Test
    void malformedInputsReturnBadRequest() throws Exception {
        log.info("Testing document HTTP input validation");
        for (String[] parameter : List.of(new String[]{"date_from", "invalid"},
                new String[]{"current_state", "INVALID"}, new String[]{"uploaded_by", "abc"},
                new String[]{"page", "0"}, new String[]{"page_size", "101"})) {
            mvc.perform(get("/api/v1/documents").param(parameter[0], parameter[1]))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(1));
        }
        mvc.perform(get("/api/v1/documents/not-a-uuid"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        verifyNoInteractions(queries);
    }

    /** Verifies inaccessible document details use the agreed 404 response envelope. */
    @Test
    void missingDetailReturnsNotFound() throws Exception {
        log.info("Testing missing document HTTP response");
        var id = UUID.randomUUID();
        when(queries.getDocument(id, 12L, org.involens_be.common.UserRole.EMPLOYEE))
                .thenThrow(new DocumentNotFoundException());
        mvc.perform(get("/api/v1/documents/" + id)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(1))
                .andExpect(jsonPath("$.error.code").value("DOCUMENT_NOT_FOUND"));
    }

    /** Verifies a missing AI result has a distinct backend error code from a missing document. */
    @Test
    void missingExtractionResultReturnsDedicatedNotFound() throws Exception {
        var id = UUID.randomUUID();
        when(queries.getExtractionResult(id, 12L, org.involens_be.common.UserRole.EMPLOYEE))
                .thenThrow(new ExtractionResultNotFoundException());
        mvc.perform(get("/api/v1/documents/" + id + "/extraction-result"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(1))
                .andExpect(jsonPath("$.error.code").value("EXTRACTION_RESULT_NOT_FOUND"));
    }

    /** Accepts an empty JSON object so a user can confirm an unchanged AI-produced invoice. */
    @Test
    void emptyObjectConfirmsExistingExtractionResult() throws Exception {
        UUID id = UUID.randomUUID();
        when(queries.updateExtractionResult(eq(id), any(), eq(12L),
                eq(org.involens_be.common.UserRole.EMPLOYEE))).thenReturn(
                org.involens_be.dto.response.UpdateExtractionResultResponse.builder()
                        .extractionResult(org.involens_be.dto.response.ExtractionResultResponse.builder()
                                .documentId(id).aiStatus(org.involens_be.common.AiStatus.SUCCESS)
                                .humanReviewRequired(false).build())
                        .invoice(org.involens_be.dto.response.InvoiceResponse.builder()
                                .documentId(id).sellerName("AI Seller").build())
                        .build());

        mvc.perform(patch("/api/v1/documents/" + id + "/extraction-result")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(0))
                .andExpect(jsonPath("$.data.extraction_result.ai_status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.invoice.seller_name").value("AI Seller"));
    }
}
