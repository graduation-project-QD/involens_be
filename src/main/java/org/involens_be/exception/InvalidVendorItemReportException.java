package org.involens_be.exception;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class InvalidVendorItemReportException extends RuntimeException {
    /** Reports invalid supplier-report filters before reading invoice data. */
    public InvalidVendorItemReportException(String message) {
        super(message);
        log.warn("Invalid supplier item report filter: {}", message);
    }
}
