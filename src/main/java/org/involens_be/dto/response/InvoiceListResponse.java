package org.involens_be.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

/** Contains one page of visible invoice extraction outcomes. */
@Getter
@Builder
public class InvoiceListResponse {
    @Expose private List<InvoiceListItemResponse> items;
    @Expose private long total;
    @Expose private int page;
    @Expose @JsonProperty("page_size") @SerializedName("page_size") private int pageSize;
}
