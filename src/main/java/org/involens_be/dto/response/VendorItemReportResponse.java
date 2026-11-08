package org.involens_be.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;
import java.math.BigDecimal;
import java.util.List;

public record VendorItemReportResponse(
        @Expose @SerializedName("total_revenue") @JsonProperty("total_revenue") BigDecimal totalRevenue,
        @Expose @SerializedName("total_invoices") @JsonProperty("total_invoices") long totalInvoices,
        @Expose @SerializedName("total_items") @JsonProperty("total_items") int totalItems,
        @Expose List<Item> items) {
    public record Item(
            @Expose @SerializedName("item_name") @JsonProperty("item_name") String itemName,
            @Expose BigDecimal quantity,
            @Expose @SerializedName("unit_price") @JsonProperty("unit_price") BigDecimal unitPrice,
            @Expose @SerializedName("min_unit_price") @JsonProperty("min_unit_price") BigDecimal minUnitPrice,
            @Expose @SerializedName("max_unit_price") @JsonProperty("max_unit_price") BigDecimal maxUnitPrice,
            @Expose @SerializedName("total_amount") @JsonProperty("total_amount") BigDecimal totalAmount,
            @Expose BigDecimal percentage) {
    }
}
