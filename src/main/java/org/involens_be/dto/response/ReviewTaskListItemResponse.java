package org.involens_be.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;
import lombok.Builder;
import lombok.Getter;
import org.involens_be.common.ReviewStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Builder
@JsonInclude(JsonInclude.Include.ALWAYS)
public class ReviewTaskListItemResponse {

    @Expose
    @JsonProperty("id")
    @SerializedName("id")
    private UUID id;

    @Expose
    @JsonProperty("document_id")
    @SerializedName("document_id")
    private UUID documentId;

    @Expose
    @JsonProperty("document_filename")
    @SerializedName("document_filename")
    private String documentFilename;

    @Expose
    @JsonProperty("vendor_name")
    @SerializedName("vendor_name")
    private String vendorName;

    @Expose
    @JsonProperty("created_at")
    @SerializedName("created_at")
    private OffsetDateTime createdAt;

    @Expose
    @JsonProperty("reason")
    @SerializedName("reason")
    private String reason;

    @Expose
    @JsonProperty("status")
    @SerializedName("status")
    private ReviewStatus status;

    @Expose
    @JsonProperty("priority")
    @SerializedName("priority")
    private Integer priority;
}
