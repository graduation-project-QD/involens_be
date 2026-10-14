package org.involens_be.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class ReviewTaskListResponse {

    @Expose
    @JsonProperty("items")
    @SerializedName("items")
    private List<ReviewTaskListItemResponse> items;

    @Expose
    @JsonProperty("total")
    @SerializedName("total")
    private long total;

    @Expose
    @JsonProperty("page")
    @SerializedName("page")
    private int page;

    @Expose
    @JsonProperty("page_size")
    @SerializedName("page_size")
    private int pageSize;
}
