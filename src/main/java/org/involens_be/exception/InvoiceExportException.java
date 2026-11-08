package org.involens_be.exception;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class InvoiceExportException extends RuntimeException {

    /** Preserves an export failure while providing a safe message for the download client. */
    public InvoiceExportException(Throwable cause) {
        this("Excel", cause);
    }

    /** Reports the failed download format while retaining the underlying export failure for diagnostics. */
    public InvoiceExportException(String format, Throwable cause) {
        super("Xuất file " + format + " thất bại. Vui lòng thử lại.", cause);
        log.error("Invoice {} export failed", format, cause);
    }
}
