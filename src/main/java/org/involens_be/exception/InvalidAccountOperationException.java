package org.involens_be.exception;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class InvalidAccountOperationException extends RuntimeException {
    /** Reports a rejected account or password change with a safe business explanation. */
    public InvalidAccountOperationException(String message) {
        super(message);
        log.warn("Account operation rejected: {}", message);
    }
}
