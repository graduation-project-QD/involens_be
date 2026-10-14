package org.involens_be.dto.request;

import com.google.gson.annotations.Expose;
import lombok.Builder;
import lombok.Getter;

import java.util.UUID;

/** Carries the document ID and object storage key to the AI extraction worker. */
@Getter
@Builder
public class SendDocumentExtract {
    @Expose
    private UUID id;

    /** Object storage key used by the worker to retrieve the document. */
    @Expose
    private String fileName;
}
