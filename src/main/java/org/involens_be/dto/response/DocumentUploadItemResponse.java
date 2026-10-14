package org.involens_be.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.involens_be.common.DocumentState;
import org.involens_be.common.UploadStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DocumentUploadItemResponse {

    @Expose
    private UUID id;

    @Expose
    @SerializedName("original_filename")
    @JsonProperty("original_filename")
    private String originalFilename;

    @Expose
    @SerializedName("mime_type")
    @JsonProperty("mime_type")
    private String mimeType;

    @Expose
    @SerializedName("file_size")
    @JsonProperty("file_size")
    private Long fileSize;

    @Expose
    @SerializedName("upload_status")
    @JsonProperty("upload_status")
    private UploadStatus uploadStatus;

    @Expose
    @SerializedName("current_state")
    @JsonProperty("current_state")
    private DocumentState currentState;

    @Expose
    @SerializedName("uploaded_at")
    @JsonProperty("uploaded_at")
    private OffsetDateTime uploadedAt;

    @Expose
    @SerializedName("error_code")
    @JsonProperty("error_code")
    private String errorCode;

    @Expose
    @SerializedName("error_message")
    @JsonProperty("error_message")
    private String errorMessage;
}
