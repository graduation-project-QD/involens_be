package org.involens_be.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;
import lombok.Builder;
import lombok.Getter;
import java.util.List;

@Getter
@Builder
public class AccountListResponse {
    @Expose
    private List<AccountListItemResponse> items;
    @Expose
    private long total;
    @Expose
    private int page;
    @Expose
    @JsonProperty("page_size")
    @SerializedName("page_size")
    private int pageSize;
}
