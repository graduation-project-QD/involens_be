package org.involens_be.exception;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class NoInvoicesToExportException extends RuntimeException {

    /** Reports an empty export selection so the frontend can display an alert. */
    public NoInvoicesToExportException() {
        super("Không có hóa đơn nào phù hợp với bộ lọc trong khoảng ngày bạn chọn.");
        log.warn("Invoice export selection contains no visible invoices");
    }
}
