package org.involens_be.exception;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class InvoiceExportException extends RuntimeException {

    /** Preserves an export failure while providing a safe message for the download client. */
    public InvoiceExportException(Throwable cause) {
        super("Xuất file Excel thất bại. Vui lòng thử lại.", cause);
        log.error("Invoice Excel export failed", cause);
    }
}
