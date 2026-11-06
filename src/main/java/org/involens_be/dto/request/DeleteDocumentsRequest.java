package org.involens_be.dto.request;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;
import java.util.UUID;

/** Identifies the documents to deactivate together within the authenticated user's scope. */
@Data
public class DeleteDocumentsRequest {
    @NotEmpty
    private List<@NotNull UUID> documentIds;
}
