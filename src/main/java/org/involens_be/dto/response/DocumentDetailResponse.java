package org.involens_be.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;
import lombok.Builder;
import lombok.Getter;
import java.util.UUID;
import java.time.OffsetDateTime;
import org.involens_be.common.CommonStatus;
import org.involens_be.common.UploadStatus;
import org.involens_be.common.DocumentState;

@Getter
@Builder
@JsonInclude(JsonInclude.Include.ALWAYS)
public class DocumentDetailResponse {

    @Expose
    @JsonProperty("id")
    @SerializedName("id")
    private UUID id;

    @Expose
    @JsonProperty("original_filename")
    @SerializedName("original_filename")
    private String originalFilename;

    @Expose
    @JsonProperty("mime_type")
    @SerializedName("mime_type")
    private String mimeType;

    @Expose
    @JsonProperty("file_size")
    @SerializedName("file_size")
    private Long fileSize;

    @Expose
    @JsonProperty("file_url")
    @SerializedName("file_url")
    private String fileUrl;

    @Expose
    @JsonProperty("status")
    @SerializedName("status")
    private CommonStatus status;

    @Expose
    @JsonProperty("upload_status")
    @SerializedName("upload_status")
    private UploadStatus uploadStatus;

    @Expose
    @JsonProperty("current_state")
    @SerializedName("current_state")
    private DocumentState currentState;

    @Expose
    @JsonProperty("uploaded_by")
    @SerializedName("uploaded_by")
    private DocumentUploaderResponse uploadedBy;

    @Expose
    @JsonProperty("uploaded_at")
    @SerializedName("uploaded_at")
    private OffsetDateTime uploadedAt;

    @Expose
    @JsonProperty("error_code")
    @SerializedName("error_code")
    private String errorCode;

    @Expose
    @JsonProperty("error_message")
    @SerializedName("error_message")
    private String errorMessage;
}
