package org.involens_be.dto.request;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class VendorItemReportRequest {
    private Long vendorId;
    private String dateFrom;
    private String dateTo;
}
