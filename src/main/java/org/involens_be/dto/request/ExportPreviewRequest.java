package org.involens_be.dto.request;

import lombok.Getter;
import lombok.Setter;
@Getter
@Setter
public class ExportPreviewRequest {

    public enum Status {
        ALL,
        APPROVED,
        PENDING_REVIEW
    }

    private String dateFrom;

    private String dateTo;

    @io.swagger.v3.oas.annotations.Parameter(description = "Optional supplier IDs; omitted or empty selects all suppliers")
    private java.util.List<Long> vendorIds;

    private Status status = Status.ALL;
}
