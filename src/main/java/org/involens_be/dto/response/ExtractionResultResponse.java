package org.involens_be.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;
import lombok.Builder;
import lombok.Getter;
import org.involens_be.common.AiStatus;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.time.LocalDate;
import java.util.UUID;
import java.util.Map;

@Getter
@Builder
@JsonInclude(JsonInclude.Include.ALWAYS)
public class ExtractionResultResponse {

    @Expose
    @JsonProperty("id")
    @SerializedName("id")
    private UUID id;

    @Expose
    @JsonProperty("document_id")
    @SerializedName("document_id")
    private UUID documentId;

    @Expose
    @JsonProperty("ai_status")
    @SerializedName("ai_status")
    private AiStatus aiStatus;

    @Expose
    @JsonProperty("retry_count")
    @SerializedName("retry_count")
    private Integer retryCount;

    @Expose
    @JsonProperty("human_review_required")
    @SerializedName("human_review_required")
    private Boolean humanReviewRequired;

    @Expose
    @JsonProperty("overall_confidence")
    @SerializedName("overall_confidence")
    private BigDecimal overallConfidence;

    @Expose @JsonProperty("seller_name") @SerializedName("seller_name") private String sellerName;
    @Expose @JsonProperty("seller_address") @SerializedName("seller_address") private String sellerAddress;
    @Expose @JsonProperty("invoice_number") @SerializedName("invoice_number") private String invoiceNumber;
    @Expose @JsonProperty("tax_code") @SerializedName("tax_code") private String taxCode;
    @Expose @JsonProperty("invoice_date") @SerializedName("invoice_date") private LocalDate invoiceDate;
    @Expose @JsonProperty("total_amount") @SerializedName("total_amount") private BigDecimal totalAmount;
    @Expose @JsonProperty("items") @SerializedName("items") private List<InvoiceItemResponse> items;

    @Expose @JsonProperty("currency") @SerializedName("currency") private String currency;
    @Expose @JsonProperty("warnings") @SerializedName("warnings") private List<?> warnings;
    /** Original AI field paths mapped to their evidence polygons, independent of reviewed item ordering. */
    @Expose @JsonProperty("polygons") @SerializedName("polygons") private Map<String, List<Object>> polygons;
    @Expose @JsonProperty("raw_json") @SerializedName("raw_json") private Map<String, Object> rawJson;

    @Expose
    @JsonProperty("error_code")
    @SerializedName("error_code")
    private String errorCode;

    @Expose
    @JsonProperty("error_message")
    @SerializedName("error_message")
    private String errorMessage;

    @Expose
    @JsonProperty("processing_started_at")
    @SerializedName("processing_started_at")
    private OffsetDateTime processingStartedAt;

    @Expose
    @JsonProperty("processing_finished_at")
    @SerializedName("processing_finished_at")
    private OffsetDateTime processingFinishedAt;
}
