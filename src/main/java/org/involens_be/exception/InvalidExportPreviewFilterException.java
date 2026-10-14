package org.involens_be.exception;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class InvalidExportPreviewFilterException extends RuntimeException {

    /** Reports an invalid invoice preview date range before aggregate data is queried. */
    public InvalidExportPreviewFilterException(String message) {
        super(message);
        log.warn("Export preview validation failed: {}", message);
    }
}
