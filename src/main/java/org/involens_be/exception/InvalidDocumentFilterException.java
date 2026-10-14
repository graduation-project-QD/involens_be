package org.involens_be.exception;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class InvalidDocumentFilterException extends RuntimeException {
    /** Reports invalid document search inputs without querying stored documents. */
    public InvalidDocumentFilterException(String message) {
        super(message);
        log.warn("Document search validation failed: {}", message);
    }
}
