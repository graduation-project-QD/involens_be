package org.involens_be.controller;

import org.involens_be.common.ReviewStatus;
import org.involens_be.common.UserRole;
import org.involens_be.dto.response.ReviewTaskListItemResponse;
import org.involens_be.dto.response.ReviewTaskListResponse;
import org.involens_be.exception.GlobalExceptionHandler;
import org.involens_be.service.ReviewTaskService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.time.LocalDate;
import org.involens_be.exception.InvalidDocumentFilterException;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ReviewTaskControllerTest {

    /** Requires POST with a JSON body and rejects malformed JSON before list execution. */
    @Test
    void requiresPostWithJsonBody() throws Exception {
        org.slf4j.LoggerFactory.getLogger(ReviewTaskControllerTest.class).info("Testing POST list body contract");
        mvc.perform(get("/api/v1/review-tasks")).andExpect(status().isMethodNotAllowed());
        mvc.perform(post("/api/v1/review-tasks").contentType("application/json"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/review-tasks").contentType("application/json").content("{"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    /** Reads supplier IDs from JSON and ignores conflicting supplier query parameters. */
    @Test
    void bindsSupplierIds() throws Exception {
        org.slf4j.LoggerFactory.getLogger(ReviewTaskControllerTest.class).info("Testing review supplier ID query binding");
        when(service.listReviewTasks(any(), eq(12L), eq(UserRole.EMPLOYEE)))
                .thenReturn(ReviewTaskListResponse.builder().items(List.of()).total(0).page(1).pageSize(20).build());
        mvc.perform(post("/api/v1/review-tasks").contentType("application/json").content("{}").content("{\"vendor_ids\":[7,9]}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/review-tasks").contentType("application/json").content("{}").param("vendor_ids", "invalid").content("{\"vendor_ids\":[7,9]}"))
                .andExpect(status().isOk());
        verify(service, times(2)).listReviewTasks(
                argThat(request -> List.of(7L, 9L).equals(request.getVendor_ids())), eq(12L), eq(UserRole.EMPLOYEE));
    }

    /** Accepts no supplier selection but rejects nonnumeric IDs before querying review tasks. */
    @Test
    void acceptsEmptyAndRejectsMalformedSupplierIds() throws Exception {
        org.slf4j.LoggerFactory.getLogger(ReviewTaskControllerTest.class).info("Testing empty and malformed review supplier IDs");
        when(service.listReviewTasks(any(), eq(12L), eq(UserRole.EMPLOYEE)))
                .thenReturn(ReviewTaskListResponse.builder().items(List.of()).total(0).page(1).pageSize(20).build());
        mvc.perform(post("/api/v1/review-tasks").contentType("application/json").content("{}").content("{\"vendor_ids\":[]}"))
                .andExpect(status().isOk());
        verify(service).listReviewTasks(argThat(request -> request.getVendor_ids() == null
                || request.getVendor_ids().isEmpty()), eq(12L), eq(UserRole.EMPLOYEE));
        clearInvocations(service);
        mvc.perform(post("/api/v1/review-tasks").contentType("application/json").content("{}").content("{\"vendor_ids\":[7,\"invalid\"]}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(1));
        verifyNoInteractions(service);
    }

    /** Serializes signed thumbnail URLs and explicit nulls for documents without previews. */
    @Test
    void listReturnsThumbnailJsonContract() throws Exception {
        org.slf4j.LoggerFactory.getLogger(ReviewTaskControllerTest.class).info("Testing thumbnail HTTP response contract");
        String url = "https://storage.example/preview.jpg?signature=test";
        var preview = org.involens_be.dto.response.ReviewTaskListItemResponse.builder().thumbnailUrl(url).build();
        var old = org.involens_be.dto.response.ReviewTaskListItemResponse.builder().build();
        when(service.listReviewTasks(any(), eq(12L), eq(UserRole.EMPLOYEE)))
                .thenReturn(ReviewTaskListResponse.builder().items(List.of(preview, old)).total(2).page(1).pageSize(8).build());
        mvc.perform(post("/api/v1/review-tasks").contentType("application/json").content("{}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].thumbnail_url").value(url))
                .andExpect(jsonPath("$.data.items[1].thumbnail_url").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.items[1]").value(org.hamcrest.Matchers.hasKey("thumbnail_url")));
    }

    private ReviewTaskService service;
    private MockMvc mvc;

    /** Configures isolated MVC serialization with an employee JWT principal. */
    @BeforeEach
    void setUp() {
        service = mock(ReviewTaskService.class);
        mvc = MockMvcBuilders.standaloneSetup(new ReviewTaskController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build();
        Jwt jwt = Jwt.withTokenValue("test").header("alg", "HS256").subject("employee@example.com")
                .claim("userId", 12L).claim("role", "EMPLOYEE").build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(jwt, null, List.of()));
    }

    /** Clears the test principal after each HTTP contract test. */
    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
    }

    /** Binds review filters and returns task and document identifiers within the pagination envelope. */
    @Test
    void listBindsStatusAndPagination() throws Exception {
        UUID taskId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        when(service.listReviewTasks(any(), eq(12L), eq(UserRole.EMPLOYEE))).thenAnswer(invocation -> {
            org.involens_be.dto.request.ReviewTaskListRequest request = invocation.getArgument(0);
            org.junit.jupiter.api.Assertions.assertEquals(ReviewStatus.PENDING, request.getStatus());
            org.junit.jupiter.api.Assertions.assertEquals("receipt", request.getQ());
            org.junit.jupiter.api.Assertions.assertEquals(LocalDate.parse("2026-10-01"), request.getDate_from());
            org.junit.jupiter.api.Assertions.assertEquals(LocalDate.parse("2026-10-27"), request.getDate_to());
            var item = ReviewTaskListItemResponse.builder().id(taskId).documentId(documentId).build();
            return ReviewTaskListResponse.builder().items(List.of(item)).total(1)
                    .page(request.getPage()).pageSize(request.getPage_size()).build();
        });

        mvc.perform(post("/api/v1/review-tasks").contentType("application/json").content("{}").param("status", "PENDING")
                        .param("page", "1").param("page_size", "20")
                        .content("{\"q\":\"receipt\"}").param("date_from", "2026-10-01").param("date_to", "2026-10-27"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(0))
                .andExpect(jsonPath("$.data.items").isArray())
                .andExpect(jsonPath("$.data.items[0].id").value(taskId.toString()))
                .andExpect(jsonPath("$.data.items[0].document_id").value(documentId.toString()))
                .andExpect(jsonPath("$.data.items[0].priority").doesNotExist())
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.page_size").value(20));
    }

    /** Rejects unsupported status values and out-of-range pagination before service execution. */
    @Test
    void invalidFiltersReturnBadRequest() throws Exception {
        mvc.perform(post("/api/v1/review-tasks").contentType("application/json").content("{}").param("status", "UNKNOWN"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(1));
        mvc.perform(post("/api/v1/review-tasks").contentType("application/json").content("{}").param("page_size", "101"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(1));
        mvc.perform(post("/api/v1/review-tasks").contentType("application/json").content("{}").param("date_from", "2026-02-30"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(1));
        verifyNoInteractions(service);
    }

    /** Returns the existing validation envelope when the service rejects a reversed upload-date range. */
    @Test
    void reversedUploadDateRangeReturnsBadRequest() throws Exception {
        when(service.listReviewTasks(any(), eq(12L), eq(UserRole.EMPLOYEE)))
                .thenThrow(new InvalidDocumentFilterException("date_from must not be after date_to"));

        mvc.perform(post("/api/v1/review-tasks").contentType("application/json").content("{}").param("date_from", "2026-10-02")
                        .param("date_to", "2026-10-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }
}
