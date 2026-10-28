package org.involens_be.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.format.annotation.DateTimeFormat;
import java.time.LocalDate;
import lombok.Getter;
import lombok.Setter;
import org.involens_be.common.ReviewStatus;

@Getter
@Setter
public class ReviewTaskListRequest {

    private ReviewStatus status;
    private String q;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate date_from;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate date_to;

    @Min(1)
    private int page = 1;

    @Min(1)
    @Max(100)
    private int page_size = 20;
}
