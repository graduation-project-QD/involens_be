package org.involens_be.dto.response;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExportPreviewResponse {

    @Expose
    @JsonProperty("total_invoices")
    @SerializedName("total_invoices")
    private Long totalInvoices;

    @Expose
    private Long approved;

    @Expose
    @JsonProperty("pending_review")
    @SerializedName("pending_review")
    private Long pendingReview;
}
