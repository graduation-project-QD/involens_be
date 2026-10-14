package org.involens_be.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class UpdateExtractionResultResponse {
    @Expose
    @JsonProperty("extraction_result")
    @SerializedName("extraction_result")
    private ExtractionResultResponse extractionResult;

    @Expose
    private InvoiceResponse invoice;
}
