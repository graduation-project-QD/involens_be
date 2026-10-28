package org.involens_be.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.involens_be.common.UserRole;
import org.involens_be.dto.request.ReviewTaskListRequest;
import org.involens_be.dto.response.ReviewTaskListResponse;
import org.involens_be.mapper.ReviewTaskMapper;
import org.involens_be.exception.InvalidDocumentFilterException;
import java.time.DateTimeException;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Locale;
import org.involens_be.repository.ReviewTaskRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewTaskService {

    private static final ZoneId VIETNAM_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final ReviewTaskRepository reviewTaskRepository;
    private final ReviewTaskMapper reviewTaskMapper;

    /** Searches review-required documents by filename or uploader and upload dates within the caller's scope. */
    @Transactional(readOnly = true)
    public ReviewTaskListResponse listReviewTasks(
            ReviewTaskListRequest request, Long userId, UserRole role) {
        boolean manager = role == UserRole.MANAGER;
        log.info("Listing review tasks. userId={}, role={}, status={}, page={}, pageSize={}",
                userId, role, request.getStatus(), request.getPage(), request.getPage_size());
        if (request.getDate_from() != null && request.getDate_to() != null
                && request.getDate_from().isAfter(request.getDate_to())) {
            log.warn("Rejecting reversed review upload-date range. userId={}", userId);
            throw new InvalidDocumentFilterException("date_from must not be after date_to");
        }
        OffsetDateTime from = request.getDate_from() == null ? null
                : request.getDate_from().atStartOfDay(VIETNAM_ZONE).toOffsetDateTime();
        OffsetDateTime until;
        try {
            until = request.getDate_to() == null ? null
                    : request.getDate_to().plusDays(1).atStartOfDay(VIETNAM_ZONE).toOffsetDateTime();
        } catch (DateTimeException exception) {
            log.warn("Rejecting unsupported review upload-date boundary. userId={}", userId);
            throw new InvalidDocumentFilterException("date_to is outside the supported date range");
        }
        var pageable = PageRequest.of(request.getPage() - 1, request.getPage_size(),
                Sort.by(Sort.Order.asc("priority"), Sort.Order.asc("createdAt")));
        var result = reviewTaskRepository.findVisibleTasks(request.getStatus(), manager, userId,
                keywordPattern(request.getQ()), from, until, pageable);
        var items = result.getContent().stream().map(reviewTaskMapper::toListItemResponse).toList();
        log.info("Review tasks loaded. userId={}, role={}, total={}, returned={}",
                userId, role, result.getTotalElements(), items.size());
        return ReviewTaskListResponse.builder().items(items).total(result.getTotalElements())
                .page(request.getPage()).pageSize(request.getPage_size()).build();
    }
    /** Prepares a literal, case-insensitive filename-or-uploader substring, ignoring blank input. */
    private String keywordPattern(String query) {
        log.debug("Preparing review filename or uploader search. filtered={}", query != null && !query.isBlank());
        if (query == null || query.isBlank()) {
            return null;
        }
        return "%" + query.trim().toLowerCase(Locale.ROOT)
                .replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    }
}
