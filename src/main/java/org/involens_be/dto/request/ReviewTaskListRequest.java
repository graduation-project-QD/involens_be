package org.involens_be.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;
import org.involens_be.common.ReviewStatus;

@Getter
@Setter
public class ReviewTaskListRequest {

    private ReviewStatus status;

    @Min(1)
    private int page = 1;

    @Min(1)
    @Max(100)
    private int page_size = 20;
}
