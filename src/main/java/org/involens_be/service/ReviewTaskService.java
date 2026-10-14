package org.involens_be.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.involens_be.common.UserRole;
import org.involens_be.dto.request.ReviewTaskListRequest;
import org.involens_be.dto.response.ReviewTaskListResponse;
import org.involens_be.mapper.ReviewTaskMapper;
import org.involens_be.repository.ReviewTaskRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewTaskService {

    private final ReviewTaskRepository reviewTaskRepository;
    private final ReviewTaskMapper reviewTaskMapper;

    /** Lists review-required documents within the authenticated user's role-based visibility scope. */
    @Transactional(readOnly = true)
    public ReviewTaskListResponse listReviewTasks(
            ReviewTaskListRequest request, Long userId, UserRole role) {
        boolean manager = role == UserRole.MANAGER;
        log.info("Listing review tasks. userId={}, role={}, status={}, page={}, pageSize={}",
                userId, role, request.getStatus(), request.getPage(), request.getPage_size());
        var pageable = PageRequest.of(request.getPage() - 1, request.getPage_size(),
                Sort.by(Sort.Order.asc("priority"), Sort.Order.asc("createdAt")));
        var result = reviewTaskRepository.findVisibleTasks(request.getStatus(), manager, userId, pageable);
        var items = result.getContent().stream().map(reviewTaskMapper::toListItemResponse).toList();
        log.info("Review tasks loaded. userId={}, role={}, total={}, returned={}",
                userId, role, result.getTotalElements(), items.size());
        return ReviewTaskListResponse.builder().items(items).total(result.getTotalElements())
                .page(request.getPage()).pageSize(request.getPage_size()).build();
    }
}
