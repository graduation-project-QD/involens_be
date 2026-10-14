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

    private Status status = Status.ALL;
}
