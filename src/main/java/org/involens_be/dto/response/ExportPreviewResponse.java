package org.involens_be.dto.response;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.involens_be.dto.request.ExportPreviewRequest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

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

    @Expose
    private List<Item> items;

    /** Carries one invoice in the same order and selection as the Excel download. */
    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Item {
        @Expose
        @JsonProperty("invoice_number")
        @SerializedName("invoice_number")
        private String invoiceNumber;

        @Expose
        @JsonProperty("seller_name")
        @SerializedName("seller_name")
        private String sellerName;

        @Expose
        @JsonProperty("invoice_date")
        @SerializedName("invoice_date")
        private LocalDate invoiceDate;

        @Expose
        @JsonProperty("total_amount")
        @SerializedName("total_amount")
        private BigDecimal totalAmount;

        @Expose
        private ExportPreviewRequest.Status status;
    }
}
