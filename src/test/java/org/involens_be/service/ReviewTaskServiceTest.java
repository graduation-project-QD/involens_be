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
import java.time.LocalDate;
import org.involens_be.exception.InvalidDocumentFilterException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@lombok.extern.slf4j.Slf4j
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
        when(reviewTasks.findVisibleTasks(eq(ReviewStatus.PENDING), eq(false), eq(12L), isNull(), isNull(), isNull(), any()))
                .thenReturn(new PageImpl<>(List.of(row)));

        var response = service.listReviewTasks(request, 12L, UserRole.EMPLOYEE);

        assertEquals(1, response.getTotal());
        assertEquals(id, response.getItems().getFirst().getId());
        assertEquals(documentId, response.getItems().getFirst().getDocumentId());
        assertNull(response.getItems().getFirst().getVendorName());
        assertEquals("Dữ liệu có độ tin cậy thấp.", response.getItems().getFirst().getReason());
        assertEquals(OffsetDateTime.parse("2026-09-10T08:16:02Z"),
                response.getItems().getFirst().getCreatedAt());
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(reviewTasks).findVisibleTasks(eq(ReviewStatus.PENDING), eq(false), eq(12L), isNull(), isNull(), isNull(), pageable.capture());
        assertEquals("priority: ASC,createdAt: ASC", pageable.getValue().getSort().toString().replace(", ", ","));
    }

    /** Translates stored backend errors and AI warnings while preserving unknown or empty diagnostics. */
    @Test
    void storedReviewReasonsAreTranslatedWithoutLosingUnknownConcerns() {
        log.info("Checking Vietnamese review reasons and diagnostic preservation");
        var mapper = Mappers.getMapper(ReviewTaskMapper.class);
        String warning = "{\"code\":\"LOW_CONFIDENCE\",\"target\":\"fields.seller\"}";
        assertEquals("Ngày hóa đơn: ngày giờ không hợp lệ.; Tên nhà cung cấp có độ tin cậy thấp.",
                mapper.vietnameseReason("fields.timestamp: invalid local date-time; " + warning));
        assertEquals("Dòng hàng 1 - Số lượng: số lượng không hợp lệ hoặc vượt giới hạn lưu trữ.",
                mapper.vietnameseReason("line_items[0].quantity: quantity cannot be stored as a finite Float"));
        assertEquals("Dòng hàng 2: thiếu dữ liệu dòng hàng.",
                mapper.vietnameseReason("line_items[1]: missing line"));
        assertEquals("Tên nhà cung cấp: thiếu trường dữ liệu.",
                mapper.vietnameseReason("fields.seller: missing field"));
        assertEquals("Độ tin cậy tổng thể phải là số trong khoảng từ 0 đến 1.",
                mapper.vietnameseReason("overall_confidence: expected a number between 0 and 1"));
        assertEquals("Địa chỉ nhà cung cấp: giá trị, trạng thái hoặc bằng chứng không nhất quán.",
                mapper.vietnameseReason("fields.address: inconsistent value, status or evidence"));
        assertEquals("Tổng tiền: số tiền không hợp lệ hoặc vượt giới hạn lưu trữ decimal(18,2).",
                mapper.vietnameseReason("fields.total_cost: amount cannot be stored as decimal(18,2)"));
        String unknown = "{\"code\":\"NEW_WARNING\",\"target\":\"fields.seller\"}";
        assertEquals(unknown, mapper.vietnameseReason(unknown));
        assertEquals("{broken", mapper.vietnameseReason("{broken"));
        assertEquals("Lý do đã có tiếng Việt", mapper.vietnameseReason("Lý do đã có tiếng Việt"));
        assertEquals("unknown: diagnostic", mapper.vietnameseReason("unknown: diagnostic"));
        assertEquals("", mapper.vietnameseReason(""));
        assertNull(mapper.vietnameseReason(null));
        log.info("Vietnamese review reasons and diagnostic preservation verified");
    }

    /** Allows managers to query the complete review queue with the requested pagination. */
    @Test
    void managerListUsesGlobalScope() {
        var request = new ReviewTaskListRequest();
        request.setPage(2);
        request.setPage_size(10);
        when(reviewTasks.findVisibleTasks(isNull(), eq(true), eq(99L), isNull(), isNull(), isNull(), any()))
                .thenReturn(new PageImpl<>(List.of()));

        var response = service.listReviewTasks(request, 99L, UserRole.MANAGER);

        assertEquals(2, response.getPage());
        assertEquals(10, response.getPageSize());
        verify(reviewTasks).findVisibleTasks(isNull(), eq(true), eq(99L), isNull(), isNull(), isNull(), any());
    }

    /** Applies literal keyword matching and an inclusive Vietnam upload day before paging owner-scoped tasks. */
    @Test
    void searchEscapesWildcardsAndConvertsInclusiveUploadDates() {
        var request = new ReviewTaskListRequest();
        request.setQ("  RECEIPT_100%!  ");
        request.setDate_from(LocalDate.parse("2026-10-01"));
        request.setDate_to(LocalDate.parse("2026-10-01"));
        var from = OffsetDateTime.parse("2026-10-01T00:00:00+07:00");
        var until = OffsetDateTime.parse("2026-10-02T00:00:00+07:00");
        when(reviewTasks.findVisibleTasks(isNull(), eq(false), eq(12L),
                eq("%receipt!_100!%!!%"), eq(from), eq(until), any()))
                .thenReturn(new PageImpl<>(List.of()));

        var response = service.listReviewTasks(request, 12L, UserRole.EMPLOYEE);

        assertEquals(0, response.getTotal());
        assertTrue(response.getItems().isEmpty());
        verify(reviewTasks).findVisibleTasks(isNull(), eq(false), eq(12L),
                eq("%receipt!_100!%!!%"), eq(from), eq(until), any());
    }

    /** Ignores whitespace-only search while allowing a single lower upload-date boundary for managers. */
    @Test
    void blankSearchAndOpenEndedDateKeepManagerScope() {
        var request = new ReviewTaskListRequest();
        request.setQ("   ");
        request.setDate_from(LocalDate.parse("2026-10-01"));
        var from = OffsetDateTime.parse("2026-10-01T00:00:00+07:00");
        when(reviewTasks.findVisibleTasks(isNull(), eq(true), eq(99L), isNull(), eq(from), isNull(), any()))
                .thenReturn(new PageImpl<>(List.of()));

        service.listReviewTasks(request, 99L, UserRole.MANAGER);

        verify(reviewTasks).findVisibleTasks(isNull(), eq(true), eq(99L), isNull(), eq(from), isNull(), any());
    }

    /** Rejects reversed dates and unsupported inclusive end dates before querying visible review tasks. */
    @Test
    void invalidUploadDateRangeDoesNotQueryRepository() {
        var request = new ReviewTaskListRequest();
        request.setDate_from(LocalDate.parse("2026-10-02"));
        request.setDate_to(LocalDate.parse("2026-10-01"));
        assertThrows(InvalidDocumentFilterException.class,
                () -> service.listReviewTasks(request, 12L, UserRole.EMPLOYEE));
        request.setDate_from(null);
        request.setDate_to(LocalDate.MAX);
        assertThrows(InvalidDocumentFilterException.class,
                () -> service.listReviewTasks(request, 12L, UserRole.EMPLOYEE));
        verifyNoInteractions(reviewTasks);
    }
}
