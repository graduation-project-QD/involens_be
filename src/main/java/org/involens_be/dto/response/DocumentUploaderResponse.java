package org.involens_be.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
@JsonInclude(JsonInclude.Include.ALWAYS)
public class DocumentUploaderResponse {

    @Expose
    @JsonProperty("id")
    @SerializedName("id")
    private Long id;

    @Expose
    @JsonProperty("full_name")
    @SerializedName("full_name")
    private String fullName;
}
