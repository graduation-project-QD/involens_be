package org.involens_be.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;
import lombok.Data;
import java.math.BigDecimal;

/** Carries one complete invoice line supplied by a reviewer. */
@Data
public class InvoiceItemRequest {
    @Expose @JsonProperty("item_name") @SerializedName("item_name") private String itemName;
    @Expose private Float quantity;
    @Expose @JsonProperty("unit_price") @SerializedName("unit_price") private BigDecimal unitPrice;
    @Expose @JsonProperty("line_total") @SerializedName("line_total") private BigDecimal lineTotal;
}
