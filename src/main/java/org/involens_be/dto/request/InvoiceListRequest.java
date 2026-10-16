package org.involens_be.dto.request;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;
import org.involens_be.common.DocumentState;
import org.springframework.format.annotation.DateTimeFormat;
import java.time.LocalDate;

/** Filters extracted invoice outcomes using the document processing state. */
@Getter
@Setter
public class InvoiceListRequest {
    private String q;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate date_from;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate date_to;
    private DocumentState current_state;
    @Min(1)
    private int page = 1;
    @Min(1)
    @Max(100)
    private int page_size = 8;

    /** Allows open-ended invoice date filters and rejects reversed inclusive date ranges. */
    @AssertTrue(message = "date_from must not be after date_to")
    public boolean isValidDateRange() {
        var logger = org.slf4j.LoggerFactory.getLogger(InvoiceListRequest.class);
        logger.debug("Validating invoice date range. from={}, to={}", date_from, date_to);
        boolean valid = date_from == null || date_to == null || !date_from.isAfter(date_to);
        if (!valid) {
            logger.warn("Rejected reversed invoice date range. from={}, to={}", date_from, date_to);
        }
        return valid;
    }

    /** Allows only completed extraction outcomes, or all three when no state is supplied. */
    @AssertTrue(message = "current_state must be EXTRACT_SUCCESS, REVIEW_REQUIRED or EXTRACT_FAILED")
    public boolean isSupportedState() {
        org.slf4j.LoggerFactory.getLogger(InvoiceListRequest.class)
                .debug("Validating invoice outcome filter. state={}", current_state);
        boolean supported = current_state == null || current_state == DocumentState.EXTRACT_SUCCESS
                || current_state == DocumentState.REVIEW_REQUIRED || current_state == DocumentState.EXTRACT_FAILED;
        if (!supported) {
            org.slf4j.LoggerFactory.getLogger(InvoiceListRequest.class)
                    .warn("Unsupported invoice outcome filter. state={}", current_state);
        }
        return supported;
    }
}
