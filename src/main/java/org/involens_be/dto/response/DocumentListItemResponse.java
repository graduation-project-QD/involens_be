package org.involens_be.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;
import lombok.Builder;
import lombok.Getter;
import java.util.UUID;
import java.time.OffsetDateTime;
import org.involens_be.common.DocumentState;

@Getter
@Builder
@JsonInclude(JsonInclude.Include.ALWAYS)
public class DocumentListItemResponse {

    @Expose
    @JsonProperty("id")
    @SerializedName("id")
    private UUID id;

    @Expose
    @JsonProperty("original_filename")
    @SerializedName("original_filename")
    private String originalFilename;

    @Expose
    @JsonProperty("uploaded_by_name")
    @SerializedName("uploaded_by_name")
    private String uploadedByName;

    @Expose
    @JsonProperty("uploaded_at")
    @SerializedName("uploaded_at")
    private OffsetDateTime uploadedAt;

    @Expose
    @JsonProperty("current_state")
    @SerializedName("current_state")
    private DocumentState currentState;
}
