package org.involens_be.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.involens_be.dto.response.BaseResponse;
import org.involens_be.dto.response.DocumentUploadItemResponse;
import org.involens_be.dto.response.DocumentUploadResponse;
import org.involens_be.dto.response.DocumentSummaryResponse;
import org.involens_be.exception.DocumentRetryException;
import org.involens_be.config.OpenApiConfig;
import org.involens_be.service.DocumentService;
import org.involens_be.service.DocumentUploadService;
import org.springframework.http.MediaType;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;
import org.involens_be.common.UserRole;
import jakarta.validation.Valid;
import org.involens_be.dto.request.DocumentListRequest;
import org.involens_be.dto.response.DocumentListResponse;
import org.involens_be.dto.response.DocumentDetailResponse;
import org.involens_be.dto.response.ExtractionResultResponse;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.involens_be.dto.request.UpdateInvoiceExtractionRequest;
import org.involens_be.dto.response.UpdateExtractionResultResponse;

@Slf4j(topic = "DOCUMENT-CONTROLLER")
@RestController
@RequestMapping("/api/v1/documents")
@RequiredArgsConstructor
@Tag(name = "Documents", description = "Document ingestion operations")
public class DocumentController {

    private final DocumentService documentService;
    private final DocumentUploadService documentUploadService;

    /** Marks a visible document inactive regardless of its processing state. */
    @Operation(summary = "Deactivate document",
            description = "Managers can deactivate any active document; employees can deactivate their own. "
                    + "All processing states are supported. Only the document status changes to INACTIVE.",
            security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH_SCHEME))
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Document deactivated"),
            @ApiResponse(responseCode = "400", description = "Invalid document UUID"),
            @ApiResponse(responseCode = "401", description = "JWT is missing or invalid"),
            @ApiResponse(responseCode = "404", description = "Document not found, inactive, or outside the caller's scope")
    })
    @DeleteMapping("/{documentId}")
    public BaseResponse<Void> deactivate(
            @PathVariable UUID documentId,
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt) {
        Long userId = readUserId(jwt);
        UserRole role = readRole(jwt);
        log.info("Received document deactivation request. documentId={}, userId={}, role={}", documentId, userId, role);
        documentService.deactivateDocument(documentId, userId, role);
        return BaseResponse.success(null);
    }

    /** Returns active document dashboard totals and daily uploads visible to the authenticated user. */
    @Operation(summary = "Get document dashboard summary",
            description = "All-time totals. Processing counts PROCESSING only; rejected counts UPLOADED_FAILED. "
                    + "Daily uploads cover today and six preceding days in Asia/Ho_Chi_Minh, oldest first. "
                    + "Employees see their own active documents; managers see all active documents.",
            security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH_SCHEME))
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Summary returned, with zero counts when empty"),
            @ApiResponse(responseCode = "401", description = "JWT is missing or invalid")
    })
    @GetMapping("/summary")
    public BaseResponse<DocumentSummaryResponse> summary(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt) {
        Long ownerId = readUserId(jwt);
        UserRole role = readRole(jwt);
        log.info("Received document summary request. ownerId={}, role={}", ownerId, role);
        var response = documentService.getSummary(ownerId, role);
        return BaseResponse.success(response);
    }

    /** Returns a filtered page of active documents uploaded by the bearer token's user. */
    @Operation(summary = "List my active documents",
            description = "Optional filters are combined with AND. filename searches the filename; q matches the filename "
                    + "or uploader full name. Both use literal case-insensitive substring matching. "
                    + "Dates use YYYY-MM-DD in Asia/Ho_Chi_Minh, inclusive of both days. "
                    + "Empty filters are ignored. Defaults: page=1, page_size=8 (maximum 100). "
                    + "Sorted by uploaded_at descending, then id descending.",
            security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH_SCHEME))
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Document page returned, possibly empty"),
            @ApiResponse(responseCode = "400", description = "Invalid filter or pagination"),
            @ApiResponse(responseCode = "401", description = "JWT is missing or invalid")
    })
    @GetMapping
    public BaseResponse<DocumentListResponse> list(
            @Valid @ModelAttribute @ParameterObject DocumentListRequest request,
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt) {
        Long ownerId = readUserId(jwt);
        UserRole role = readRole(jwt);
        log.info("Received document list request.");
        var response = documentService.listDocuments(request, ownerId, role);
        return BaseResponse.success(response);
    }

    /** Returns active document detail without exposing another uploader's documents. */
    @Operation(summary = "Get my active document",
            description = "Includes file_url for stored images and PDFs, valid for one hour; null when no file is stored.",
            security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH_SCHEME))
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Document detail returned"),
            @ApiResponse(responseCode = "400", description = "Invalid document UUID"),
            @ApiResponse(responseCode = "401", description = "JWT is missing or invalid"),
            @ApiResponse(responseCode = "404", description = "Document not found, inactive, or owned by another user"),
            @ApiResponse(responseCode = "500", description = "File URL creation failed")
    })
    @GetMapping("/{documentId}")
    public BaseResponse<DocumentDetailResponse> detail(
            @PathVariable UUID documentId,
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt) {
        Long ownerId = readUserId(jwt);
        UserRole role = readRole(jwt);
        var response = documentService.getDocument(documentId, ownerId, role);
        log.info("Document detail request completed. documentId={}", documentId);
        return BaseResponse.success(response);
    }

    /** Returns the AI extraction outcome for an active document owned by the bearer token's user. */
    @Operation(summary = "Get my document extraction result",
            description = "AI processing failures are returned as data with ai_status, error_code, and error_message.",
            security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH_SCHEME))
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Extraction result returned, including an AI failure outcome"),
            @ApiResponse(responseCode = "400", description = "Invalid document UUID"),
            @ApiResponse(responseCode = "401", description = "JWT is missing or invalid"),
            @ApiResponse(responseCode = "404", description = "Document or extraction result not found")
    })
    @GetMapping("/{documentId}/extraction-result")
    public BaseResponse<ExtractionResultResponse> extractionResult(
            @PathVariable UUID documentId,
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt) {
        Long ownerId = readUserId(jwt);
        UserRole role = readRole(jwt);
        var response = documentService.getExtractionResult(documentId, ownerId, role);
        log.info("Extraction result request completed. ownerId={}, documentId={}", ownerId, documentId);
        return BaseResponse.success(response);
    }

    /**
     * Applies human-reviewed invoice corrections and completes the extraction review workflow.
     * An empty JSON object confirms an existing invoice without changing its extracted fields.
     */
    @Operation(summary = "Update extracted invoice",
            description = "Partially updates invoice fields, or confirms an existing AI result with an empty object.",
            security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH_SCHEME))
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Invoice updated and review completed"),
            @ApiResponse(responseCode = "400", description = "Invoice fields are invalid"),
            @ApiResponse(responseCode = "401", description = "JWT is missing or invalid"),
            @ApiResponse(responseCode = "404", description = "Document or extraction result not found")
    })
    @PatchMapping("/{documentId}/extraction-result")
    public BaseResponse<UpdateExtractionResultResponse> updateExtractionResult(
            @PathVariable UUID documentId,
            @RequestBody UpdateInvoiceExtractionRequest request,
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt) {
        Long reviewerId = readUserId(jwt);
        UserRole role = readRole(jwt);
        var response = documentService.updateExtractionResult(documentId, request, reviewerId, role);
        log.info("Extracted invoice update completed. documentId={}, reviewerId={}", documentId, reviewerId);
        return BaseResponse.success(response);
    }

    /**
     * Accepts a multipart document batch and returns the persisted result of every submitted file.
     *
     * @param files multipart parts named files
     * @param jwt authenticated access token containing the uploader's user ID
     * @return successful batch response including per-file validation failures
    */
    @Operation(
            summary = "Upload documents",
            description = "Uploads JPG, JPEG, PNG, or PDF files. Each file may be up to 10MB.",
            security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH_SCHEME))
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Batch processed; validation failures are returned per item"),
            @ApiResponse(responseCode = "400", description = "Multipart request is invalid"),
            @ApiResponse(responseCode = "401", description = "JWT is missing or invalid"),
            @ApiResponse(responseCode = "500", description = "Storage or persistence failed and the batch was rolled back")
    })
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public BaseResponse<DocumentUploadResponse> upload(
            @Parameter(description = "One or more JPG, JPEG, PNG, or PDF files", required = true)
            @RequestParam("files") List<MultipartFile> files,
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt) {
        Long uploadedById = readUserId(jwt);
        DocumentUploadResponse response = documentUploadService.uploadDocuments(files, uploadedById);
        log.info("Document upload request completed successfully. uploadedById={}, fileCount={}",
                uploadedById, response.getItems().size());
        return BaseResponse.success(response);
    }

    /** Resubmits an authorized document's stored file to AI and returns its processing state. */
    @Operation(summary = "Retry document extraction",
            description = "Resends the existing document ID and MinIO object key without uploading a new file. "
                    + "Only UPLOADED and EXTRACT_FAILED documents with a stored file are eligible. "
                    + "Employees can retry their own active documents; managers can retry all active documents. "
                    + "Existing extraction, invoice, and review data are retained.",
            security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH_SCHEME))
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Document resent to AI and marked PROCESSING"),
            @ApiResponse(responseCode = "400", description = "Invalid document UUID"),
            @ApiResponse(responseCode = "401", description = "JWT is missing or invalid"),
            @ApiResponse(responseCode = "404", description = "Document not found, inactive, or outside the caller's scope"),
            @ApiResponse(responseCode = "409", description = "Document state or stored file does not allow retry"),
            @ApiResponse(responseCode = "503", description = "RabbitMQ unavailable, misconfigured, or dispatch failed"),
            @ApiResponse(responseCode = "500", description = "Document state could not be persisted")
    })
    @PostMapping("/{documentId}/retry-extraction")
    public BaseResponse<DocumentUploadItemResponse> retryExtraction(
            @PathVariable UUID documentId,
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt) {
        Long userId = readUserId(jwt);
        UserRole role = readRole(jwt);

        try {
            DocumentUploadItemResponse response = documentUploadService.retryExtraction(documentId, userId, role);
            log.info("Document extraction retry request completed. documentId={}, userId={}", documentId, userId);
            return BaseResponse.success(response);
        } catch (DataAccessException | TransactionException exception) {
            log.error("Document extraction retry transaction failed. documentId={}", documentId, exception);
            throw new DocumentRetryException(DocumentRetryException.Reason.PERSISTENCE_FAILED,
                    "Document extraction retry could not be persisted", exception);
        }
    }

    /**
     * Reads the authenticated database user ID embedded in an application-issued JWT.
     *
     * @param jwt validated bearer token
     * @return authenticated user's database identifier
     */
    private Long readUserId(Jwt jwt) {
        Object userIdClaim = jwt.getClaim("userId");
        if (userIdClaim instanceof Number number) {
            return number.longValue();
        }
        log.error("Authenticated token does not contain a numeric userId claim. subject={}", jwt.getSubject());
        throw new IllegalStateException("Authenticated token does not contain a valid userId");
    }

    /** Reads the application role embedded in an application-issued JWT. */
    private UserRole readRole(Jwt jwt) {
        String roleClaim = jwt.getClaimAsString("role");
        try {
            return UserRole.valueOf(roleClaim);
        } catch (IllegalArgumentException | NullPointerException exception) {
            log.error("Authenticated token does not contain a valid role claim. subject={}", jwt.getSubject());
            throw new IllegalStateException("Authenticated token does not contain a valid role");
        }
    }
}
