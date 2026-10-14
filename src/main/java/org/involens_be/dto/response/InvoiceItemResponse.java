package org.involens_be.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;
import lombok.Builder;
import lombok.Getter;
import java.math.BigDecimal;

/** Exposes persisted invoice line values to the review screen. */
@Getter @Builder
public class InvoiceItemResponse {
    @Expose @JsonProperty("item_name") @SerializedName("item_name") private String itemName;
    @Expose private Float quantity;
    @Expose @JsonProperty("unit_price") @SerializedName("unit_price") private BigDecimal unitPrice;
    @Expose @JsonProperty("line_total") @SerializedName("line_total") private BigDecimal lineTotal;
}
