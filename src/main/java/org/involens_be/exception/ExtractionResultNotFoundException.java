package org.involens_be.exception;

public class ExtractionResultNotFoundException extends RuntimeException {

    /** Creates the stable business error used when a document has no extraction result. */
    public ExtractionResultNotFoundException() {
        super("Extraction result not found");
    }
}
