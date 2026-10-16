package org.involens_be.dto.response;

import org.involens_be.common.DocumentState;
import java.util.UUID;

/** Public status notification containing only document identity, state and failure details. */
public record DocumentStatusResponse(UUID documentId, DocumentState currentState,
                                     String errorCode, String errorMessage) {
}
