package org.involens_be.exception;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class InvoiceConflictException extends RuntimeException {

    /** Reports a stale invoice edit so the reviewer can reload the latest saved values. */
    public InvoiceConflictException(String message) {
        super(message);
        log.warn("Invoice update rejected due to concurrent changes: {}", message);
    }
}
