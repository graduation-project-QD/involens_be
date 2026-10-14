package org.involens_be.exception;

public class DocumentUploadBatchException extends RuntimeException {

    /**
     * Represents a technical failure that requires the complete upload batch to be rolled back.
     *
     * @param message stable explanation returned to the API client
     * @param cause storage or persistence failure that interrupted the batch
     */
    public DocumentUploadBatchException(String message, Throwable cause) {
        super(message, cause);
    }
}
