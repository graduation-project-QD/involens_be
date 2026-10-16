package org.involens_be.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;
import lombok.Builder;
import lombok.Getter;
import org.involens_be.common.DocumentState;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Displays an extracted invoice or a failed extraction that has no invoice yet. */
@Getter
@Builder
@JsonInclude(JsonInclude.Include.ALWAYS)
public class InvoiceListItemResponse {
    @Expose private UUID id;
    @Expose @JsonProperty("document_id") @SerializedName("document_id") private UUID documentId;
    @Expose @JsonProperty("original_filename") @SerializedName("original_filename") private String originalFilename;
    @Expose @JsonProperty("seller_name") @SerializedName("seller_name") private String sellerName;
    @Expose @JsonProperty("invoice_number") @SerializedName("invoice_number") private String invoiceNumber;
    @Expose @JsonProperty("invoice_date") @SerializedName("invoice_date") private LocalDate invoiceDate;
    @Expose @JsonProperty("total_amount") @SerializedName("total_amount") private BigDecimal totalAmount;
    @Expose private String currency;
    @Expose @JsonProperty("uploaded_by_name") @SerializedName("uploaded_by_name") private String uploadedByName;
    @Expose @JsonProperty("uploaded_at") @SerializedName("uploaded_at") private OffsetDateTime uploadedAt;
    @Expose @JsonProperty("current_state") @SerializedName("current_state") private DocumentState currentState;
    @Expose @JsonProperty("error_code") @SerializedName("error_code") private String errorCode;
    @Expose @JsonProperty("error_message") @SerializedName("error_message") private String errorMessage;
}
