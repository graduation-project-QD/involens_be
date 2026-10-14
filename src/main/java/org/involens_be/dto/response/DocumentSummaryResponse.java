package org.involens_be.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;
import java.util.List;

@Getter
@Builder
public class DocumentSummaryResponse {
    @Expose private long total;
    @Expose private long processing;
    @Expose
    @JsonProperty("review_required")
    @SerializedName("review_required")
    private long reviewRequired;
    @Expose private long success;
    @Expose private long rejected;
    @Expose private long failed;
    @Expose
    @JsonProperty("daily_uploads")
    @SerializedName("daily_uploads")
    private List<DailyUploads> dailyUploads;

    @Getter
    @Builder
    public static class DailyUploads {
        @Expose private LocalDate date;
        @Expose private long count;
    }
}
