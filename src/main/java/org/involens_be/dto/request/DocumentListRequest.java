package org.involens_be.dto.request;

import lombok.Getter;
import lombok.Setter;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import org.involens_be.common.DocumentState;
import org.springframework.format.annotation.DateTimeFormat;
import java.time.LocalDate;

@Getter
@Setter
public class DocumentListRequest {
    private DocumentState current_state;
    private String filename;
    private Long uploaded_by;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate date_from;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate date_to;
    private String q;
    @Min(1)
    private int page = 1;
    @Min(1)
    @Max(100)
    private int page_size = 8;
}
