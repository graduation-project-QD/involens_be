package org.involens_be.service;

import org.involens_be.dto.response.DocumentStatusResponse;

/** Immutable committed-state snapshot with the owner needed for notification authorization. */
public record DocumentStatusChangedEvent(Long ownerId, DocumentStatusResponse payload) {
}
