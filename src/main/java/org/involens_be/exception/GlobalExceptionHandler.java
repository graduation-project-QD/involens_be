package org.involens_be.exception;

import lombok.extern.slf4j.Slf4j;
import org.involens_be.dto.response.BaseResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.security.access.AccessDeniedException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** Returns the account-not-found envelope for manager account operations. */
    @ExceptionHandler(AccountNotFoundException.class)
    public ResponseEntity<BaseResponse<Void>> handleAccountNotFound(AccountNotFoundException exception) {
        log.warn("Returning account not found response");
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(BaseResponse.error(null, "ACCOUNT_NOT_FOUND", exception.getMessage()));
    }

    /** Returns approved validation failures for account and password operations. */
    @ExceptionHandler(InvalidAccountOperationException.class)
    public ResponseEntity<BaseResponse<Void>> handleInvalidAccountOperation(InvalidAccountOperationException exception) {
        log.warn("Returning invalid account operation response: {}", exception.getMessage());
        return ResponseEntity.badRequest().body(BaseResponse.error(null, "VALIDATION_ERROR", exception.getMessage()));
    }

    /** Rejects malformed account bodies, including explicit null fields and invalid role values. */
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<BaseResponse<Void>> handleUnreadableRequest(
            org.springframework.http.converter.HttpMessageNotReadableException exception) {
        log.warn("Returning malformed request body response");
        return ResponseEntity.badRequest().body(BaseResponse.error(null, "VALIDATION_ERROR", "Invalid request body"));
    }

    /** Returns an empty-selection message that the frontend can show instead of saving a workbook. */
    @ExceptionHandler(NoInvoicesToExportException.class)
    public ResponseEntity<BaseResponse<Void>> handleNoInvoicesToExport(NoInvoicesToExportException exception) {
        log.warn("Returning empty invoice export response");
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(BaseResponse.error(null, "NO_INVOICES_TO_EXPORT", exception.getMessage()));
    }

    /** Returns a safe server-error response when invoice workbook creation fails. */
    @ExceptionHandler(InvoiceExportException.class)
    public ResponseEntity<BaseResponse<Void>> handleInvoiceExport(InvoiceExportException exception) {
        log.error("Returning invoice Excel export failure response", exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(BaseResponse.error(null, "INVOICE_EXPORT_FAILED", exception.getMessage()));
    }

    /** Returns a validation error when invoice correction data cannot produce a valid invoice. */
    @ExceptionHandler(InvalidInvoiceUpdateException.class)
    public ResponseEntity<BaseResponse<Void>> handleInvalidInvoiceUpdate(InvalidInvoiceUpdateException exception) {
        log.warn("Returning invalid invoice update response: {}", exception.getMessage());
        return ResponseEntity.badRequest()
                .body(BaseResponse.error(null, "VALIDATION_ERROR", exception.getMessage()));
    }

    /** Returns a conflict response when account creation uses an email that is already registered. */
    @ExceptionHandler(EmailAlreadyExistsException.class)
    public ResponseEntity<BaseResponse<Void>> handleEmailAlreadyExists(EmailAlreadyExistsException exception) {
        log.warn("Returning duplicate account email response: {}", exception.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(BaseResponse.error(null, "EMAIL_ALREADY_EXISTS", exception.getMessage()));
    }

    /** Returns a forbidden response when a non-manager attempts a manager-only business operation. */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<BaseResponse<Void>> handleAccessDenied(AccessDeniedException exception) {
        log.warn("Returning manager-only access denied response: {}", exception.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(BaseResponse.error(null, "ACCESS_DENIED", exception.getMessage()));
    }

    /** Returns a validation error when export-preview filters cannot form a valid invoice selection. */
    @ExceptionHandler(InvalidExportPreviewFilterException.class)
    public ResponseEntity<BaseResponse<Void>> handleExportPreviewFilter(
            InvalidExportPreviewFilterException exception) {
        log.warn("Returning invalid export preview filter response: {}", exception.getMessage());
        return ResponseEntity.badRequest().body(BaseResponse.error(null, "VALIDATION_ERROR", exception.getMessage()));
    }

    /** Returns a distinct not-found error when an accessible document has no AI extraction outcome. */
    @ExceptionHandler(ExtractionResultNotFoundException.class)
    public ResponseEntity<BaseResponse<Void>> handleExtractionResultNotFound(
            ExtractionResultNotFoundException exception) {
        log.warn("Returning extraction result not found response");
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(BaseResponse.error(null, "EXTRACTION_RESULT_NOT_FOUND", exception.getMessage()));
    }

    /** Returns the same not-found response for missing, inactive, and other users' documents. */
    @ExceptionHandler(DocumentNotFoundException.class)
    public ResponseEntity<BaseResponse<Void>> handleDocumentNotFound(DocumentNotFoundException exception) {
        log.warn("Returning document not found response");
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(BaseResponse.error(null, "DOCUMENT_NOT_FOUND", exception.getMessage()));
    }

    /** Returns the approved validation, dispatch, or persistence failure for an extraction retry. */
    @ExceptionHandler(DocumentRetryException.class)
    public ResponseEntity<BaseResponse<Void>> handleDocumentRetry(DocumentRetryException exception) {
        HttpStatus status = switch (exception.getReason()) {
            case NOT_ALLOWED -> HttpStatus.CONFLICT;
            case DISPATCH_FAILED -> HttpStatus.SERVICE_UNAVAILABLE;
            case PERSISTENCE_FAILED -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
        String errorCode = switch (exception.getReason()) {
            case NOT_ALLOWED -> "DOCUMENT_RETRY_NOT_ALLOWED";
            case DISPATCH_FAILED -> "DOCUMENT_RETRY_DISPATCH_FAILED";
            case PERSISTENCE_FAILED -> "DOCUMENT_RETRY_FAILED";
        };
        if (exception.getReason() == DocumentRetryException.Reason.NOT_ALLOWED) {
            log.warn("Returning document extraction retry rejection: {}", exception.getMessage());
        } else {
            log.error("Returning document extraction retry failure. reason={}", exception.getReason(), exception);
        }
        return ResponseEntity.status(status).body(BaseResponse.error(null, errorCode, exception.getMessage()));
    }

    /** Reports invalid document date ranges and pagination as a client validation failure. */
    @ExceptionHandler(InvalidDocumentFilterException.class)
    public ResponseEntity<BaseResponse<Void>> handleDocumentFilter(InvalidDocumentFilterException exception) {
        log.warn("Returning invalid document filter response: {}", exception.getMessage());
        return ResponseEntity.badRequest().body(BaseResponse.error(null, "VALIDATION_ERROR", exception.getMessage()));
    }

    /** Converts malformed query values or path identifiers into a consistent bad-request response. */
    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    public ResponseEntity<BaseResponse<Void>> handleArgumentTypeMismatch(
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException exception) {
        log.warn("Invalid API parameter type. parameter={}", exception.getName());
        return ResponseEntity.badRequest()
                .body(BaseResponse.error(null, "VALIDATION_ERROR", "Invalid parameter: " + exception.getName()));
    }

    /**
     * Converts a technical document storage or persistence failure into a batch-level server error.
     *
     * @param exception upload failure after batch rollback and object storage compensation
     * @return HTTP 500 response indicating that no batch result was committed
     */
    @ExceptionHandler(DocumentUploadBatchException.class)
    public ResponseEntity<BaseResponse<Void>> handleDocumentUploadBatch(DocumentUploadBatchException exception) {
        log.error("Returning document batch upload failure response", exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(BaseResponse.error(null, "DOCUMENT_UPLOAD_FAILED", exception.getMessage()));
    }

    /**
     * Converts invalid login credentials into a stable unauthorized API response.
     *
     * @param exception authentication failure
     * @return HTTP 401 error response
     */
    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<BaseResponse<Void>> handleInvalidCredentials(InvalidCredentialsException exception) {
        log.warn("Returning authentication failure response: {}", exception.getMessage());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(BaseResponse.error(null, "AUTH_INVALID_CREDENTIALS", exception.getMessage()));
    }

    /**
     * Converts request validation failures into a bad-request API response.
     *
     * @param exception validation failure containing rejected fields
     * @return HTTP 400 error response
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<BaseResponse<Void>> handleValidation(MethodArgumentNotValidException exception) {
        String message = exception.getBindingResult().getFieldErrors().isEmpty()
                ? "Request is invalid"
                : exception.getBindingResult().getFieldErrors().getFirst().getDefaultMessage();
        log.warn("Returning request validation failure response: {}", message);
        return ResponseEntity.badRequest()
                .body(BaseResponse.error(null, "VALIDATION_ERROR", message));
    }
}
