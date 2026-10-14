package org.involens_be.exception;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class DocumentNotFoundException extends RuntimeException {
    /** Reports that no active document is visible to the requesting owner. */
    public DocumentNotFoundException() {
        super("Document not found");
        log.warn("Requested document was not found within the owner's active documents");
    }
}
