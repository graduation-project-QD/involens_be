package org.involens_be.exception;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class InvalidInvoiceUpdateException extends RuntimeException {

    /** Reports invalid invoice correction data before the review outcome is persisted. */
    public InvalidInvoiceUpdateException(String message) {
        super(message);
        log.warn("Invoice correction validation failed: {}", message);
    }
}
