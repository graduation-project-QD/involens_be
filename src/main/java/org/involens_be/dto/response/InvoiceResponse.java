package org.involens_be.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.List;

@Getter
@Builder
@JsonInclude(JsonInclude.Include.ALWAYS)
public class InvoiceResponse {
    @Expose private UUID id;
    @Expose @JsonProperty("document_id") @SerializedName("document_id") private UUID documentId;
    @Expose @JsonProperty("extraction_result_id") @SerializedName("extraction_result_id")
    private UUID extractionResultId;
    @Expose @JsonProperty("seller_name") @SerializedName("seller_name") private String sellerName;
    @Expose @JsonProperty("seller_address") @SerializedName("seller_address") private String sellerAddress;
    @Expose @JsonProperty("invoice_number") @SerializedName("invoice_number") private String invoiceNumber;
    @Expose @JsonProperty("tax_code") @SerializedName("tax_code") private String taxCode;
    @Expose @JsonProperty("invoice_date") @SerializedName("invoice_date") private LocalDate invoiceDate;
    @Expose @JsonProperty("total_amount") @SerializedName("total_amount") private BigDecimal totalAmount;
    @Expose private String currency;
    @Expose @JsonProperty("items") @SerializedName("items") private List<InvoiceItemResponse> items;
    @Expose @JsonProperty("approved_by") @SerializedName("approved_by") private Long approvedById;
    @Expose @JsonProperty("approved_at") @SerializedName("approved_at") private OffsetDateTime approvedAt;
}
