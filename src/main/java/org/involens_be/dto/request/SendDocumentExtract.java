package org.involens_be.dto.request;

import com.google.gson.annotations.Expose;
import lombok.Builder;
import lombok.Getter;

import java.util.UUID;

/** Carries the document ID and signed download URL to the AI extraction worker. */
@Getter
@Builder
public class SendDocumentExtract {
    @Expose
    private UUID id;

    /** Hour-long signed GET URL used by the worker to download the image or PDF. */
    @Expose
    private String url;
}
