package org.involens_be.service;

import org.involens_be.common.ReviewStatus;
import org.involens_be.common.UserRole;
import org.involens_be.dto.request.ReviewTaskListRequest;
import org.involens_be.mapper.ReviewTaskMapper;
import org.involens_be.repository.ReviewTaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReviewTaskServiceTest {
    @Mock private ReviewTaskRepository reviewTasks;
    private ReviewTaskService service;

    /** Creates an isolated review-task service with the production mapper. */
    @BeforeEach
    void setUp() {
        service = new ReviewTaskService(reviewTasks, Mappers.getMapper(ReviewTaskMapper.class));
    }

    /** Restricts employees to owner-scoped querying and maps nullable vendor data and UTC timestamps. */
    @Test
    void employeeListUsesOwnerScopeAndMapsResponse() {
        var request = new ReviewTaskListRequest();
        request.setStatus(ReviewStatus.PENDING);
        UUID id = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        var row = new ReviewTaskRepository.ReviewTaskRow(id, documentId, "receipt.jpg", null,
                LocalDateTime.parse("2026-09-10T08:16:02"), "LOW_CONFIDENCE",
                ReviewStatus.PENDING, 2);
        when(reviewTasks.findVisibleTasks(eq(ReviewStatus.PENDING), eq(false), eq(12L), any()))
                .thenReturn(new PageImpl<>(List.of(row)));

        var response = service.listReviewTasks(request, 12L, UserRole.EMPLOYEE);

        assertEquals(1, response.getTotal());
        assertEquals(id, response.getItems().getFirst().getId());
        assertEquals(documentId, response.getItems().getFirst().getDocumentId());
        assertNull(response.getItems().getFirst().getVendorName());
        assertEquals(OffsetDateTime.parse("2026-09-10T08:16:02Z"),
                response.getItems().getFirst().getCreatedAt());
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(reviewTasks).findVisibleTasks(eq(ReviewStatus.PENDING), eq(false), eq(12L), pageable.capture());
        assertEquals("priority: ASC,createdAt: ASC", pageable.getValue().getSort().toString().replace(", ", ","));
    }

    /** Allows managers to query the complete review queue with the requested pagination. */
    @Test
    void managerListUsesGlobalScope() {
        var request = new ReviewTaskListRequest();
        request.setPage(2);
        request.setPage_size(10);
        when(reviewTasks.findVisibleTasks(isNull(), eq(true), eq(99L), any()))
                .thenReturn(new PageImpl<>(List.of()));

        var response = service.listReviewTasks(request, 99L, UserRole.MANAGER);

        assertEquals(2, response.getPage());
        assertEquals(10, response.getPageSize());
        verify(reviewTasks).findVisibleTasks(isNull(), eq(true), eq(99L), any());
    }
}
