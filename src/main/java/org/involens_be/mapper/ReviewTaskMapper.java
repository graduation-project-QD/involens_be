package org.involens_be.mapper;

import org.involens_be.dto.response.ReviewTaskListItemResponse;
import org.involens_be.repository.ReviewTaskRepository;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@Mapper(componentModel = "spring")
public interface ReviewTaskMapper {

    /** Maps joined review-task data to the public list item and exposes its creation time as UTC. */
    @Mapping(target = "createdAt", expression = "java(toUtc(row.createdAt()))")
    ReviewTaskListItemResponse toListItemResponse(ReviewTaskRepository.ReviewTaskRow row);

    /** Treats persisted review-task timestamps as UTC and adds an explicit UTC offset. */
    default OffsetDateTime toUtc(LocalDateTime timestamp) {
        return timestamp == null ? null : timestamp.atOffset(ZoneOffset.UTC);
    }
}
