package org.involens_be.exception;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Getter
@Slf4j
public class DocumentRetryException extends RuntimeException {

    public enum Reason {
        NOT_ALLOWED,
        DISPATCH_FAILED,
        PERSISTENCE_FAILED
    }

    private final Reason reason;

    /** Reports why an existing document could not be submitted for AI extraction again. */
    public DocumentRetryException(Reason reason, String message) {
        super(message);
        this.reason = reason;
        log.warn("Document extraction retry failed. reason={}, message={}", reason, message);
    }

    /** Preserves the dispatch or persistence failure that prevented an extraction retry. */
    public DocumentRetryException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
        log.warn("Document extraction retry failed. reason={}, message={}", reason, message);
    }
}
