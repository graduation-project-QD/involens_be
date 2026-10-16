package org.involens_be.controller;

import lombok.extern.slf4j.Slf4j;
import org.involens_be.common.DocumentState;
import org.involens_be.common.UserRole;
import org.involens_be.dto.request.InvoiceListRequest;
import org.involens_be.dto.response.InvoiceListItemResponse;
import org.involens_be.dto.response.InvoiceListResponse;
import org.involens_be.exception.GlobalExceptionHandler;
import org.involens_be.service.InvoiceService;
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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Verifies the dedicated invoice HTTP contract and rejects unsupported filters before querying. */
@Slf4j
class InvoiceControllerTest {
    private InvoiceService service;
    private MockMvc mvc;

    /** Binds inclusive invoice date ranges, including either open end and a single calendar day. */
    @Test
    void acceptsInvoiceDateBounds() throws Exception {
        log.info("Testing invoice date query binding");
        when(service.listInvoices(any(), eq(99L), eq(UserRole.MANAGER))).thenAnswer(invocation -> {
            InvoiceListRequest request = invocation.getArgument(0);
            java.time.LocalDate day = java.time.LocalDate.of(2026, 10, 15);
            assertTrue(request.getDate_from() == null || request.getDate_from().equals(day));
            assertTrue(request.getDate_to() == null || request.getDate_to().equals(day));
            assertTrue(request.getDate_from() != null || request.getDate_to() != null);
            return InvoiceListResponse.builder().items(List.of()).total(0).page(1).pageSize(8).build();
        });
        mvc.perform(get("/api/v1/invoices").param("date_from", "2026-10-15"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/invoices").param("date_to", "2026-10-15"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/invoices").param("date_from", "2026-10-15").param("date_to", "2026-10-15"))
                .andExpect(status().isOk());
        verify(service, times(3)).listInvoices(any(), eq(99L), eq(UserRole.MANAGER));
    }

    /** Rejects reversed ranges and malformed calendar dates before executing an invoice query. */
    @Test
    void rejectsInvalidInvoiceDates() throws Exception {
        log.info("Testing invalid invoice date filters");
        mvc.perform(get("/api/v1/invoices").param("date_from", "2026-10-16").param("date_to", "2026-10-15"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        for (String parameter : List.of("date_from", "date_to")) {
            for (String value : List.of("not-a-date", "2026-02-30", "15/10/2026")) {
                mvc.perform(get("/api/v1/invoices").param(parameter, value))
                        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
            }
        }
        verifyNoInteractions(service);
    }

    /** Binds filename q alongside state and page filters without changing the response envelope. */
    @Test
    void bindsFilenameQuery() throws Exception {
        log.info("Testing invoice filename query binding");
        when(service.listInvoices(any(), eq(99L), eq(UserRole.MANAGER))).thenAnswer(invocation -> {
            InvoiceListRequest request = invocation.getArgument(0);
            assertEquals("mock-invoice-dev-6", request.getQ());
            assertEquals(DocumentState.EXTRACT_SUCCESS, request.getCurrent_state());
            assertEquals(2, request.getPage());
            assertEquals(10, request.getPage_size());
            return InvoiceListResponse.builder().items(List.of()).total(0).page(2).pageSize(10).build();
        });
        mvc.perform(get("/api/v1/invoices").param("q", "mock-invoice-dev-6")
                        .param("current_state", "EXTRACT_SUCCESS").param("page", "2").param("page_size", "10"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value(0))
                .andExpect(jsonPath("$.data.items").isEmpty()).andExpect(jsonPath("$.data.total").value(0));
    }

    /** Sets up the invoice controller with an authenticated manager and the existing validation handler. */
    @BeforeEach
    void setUp() {
        log.info("Preparing invoice HTTP contract test");
        service = mock(InvoiceService.class);
        mvc = MockMvcBuilders.standaloneSetup(new InvoiceController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build();
        Jwt jwt = Jwt.withTokenValue("test").header("alg", "HS256").subject("manager@example.com")
                .claim("userId", 99L).claim("role", "MANAGER").build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(jwt, null, List.of()));
    }

    /** Clears the authenticated test account to avoid leaking it into other API tests. */
    @AfterEach
    void cleanUp() {
        log.debug("Clearing invoice HTTP test principal");
        SecurityContextHolder.clearContext();
    }

    /** Binds each supported extraction state and exposes seller_name in the dedicated invoice response. */
    @Test
    void acceptsAllSupportedStates() throws Exception {
        log.info("Testing accepted invoice outcome states");
        UUID documentId = UUID.randomUUID();
        when(service.listInvoices(any(), eq(99L), eq(UserRole.MANAGER))).thenAnswer(invocation -> {
            InvoiceListRequest request = invocation.getArgument(0);
            var item = InvoiceListItemResponse.builder().documentId(documentId).sellerName("Seller")
                    .currentState(request.getCurrent_state()).build();
            return InvoiceListResponse.builder().items(List.of(item)).total(1)
                    .page(request.getPage()).pageSize(request.getPage_size()).build();
        });
        for (String state : List.of("EXTRACT_SUCCESS", "REVIEW_REQUIRED", "EXTRACT_FAILED")) {
            mvc.perform(get("/api/v1/invoices").param("current_state", state).param("page", "2").param("page_size", "10"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.status").value(0))
                    .andExpect(jsonPath("$.data.items[0].document_id").value(documentId.toString()))
                    .andExpect(jsonPath("$.data.items[0].seller_name").value("Seller"))
                    .andExpect(jsonPath("$.data.items[0].current_state").value(state))
                    .andExpect(jsonPath("$.data.page").value(2)).andExpect(jsonPath("$.data.page_size").value(10));
        }
    }

    /** Uses the default page and no state filter while serializing failed invoice fields as explicit nulls. */
    @Test
    void defaultsAndFailureNulls() throws Exception {
        log.info("Testing default invoice page and missing invoice data");
        when(service.listInvoices(any(), eq(99L), eq(UserRole.MANAGER))).thenAnswer(invocation -> {
            InvoiceListRequest request = invocation.getArgument(0);
            assertNull(request.getCurrent_state());
            assertEquals(1, request.getPage());
            assertEquals(8, request.getPage_size());
            var item = InvoiceListItemResponse.builder().documentId(UUID.randomUUID())
                    .currentState(DocumentState.EXTRACT_FAILED).errorCode("IMAGE_DOWNLOAD_FAILED").build();
            return InvoiceListResponse.builder().items(List.of(item)).total(1).page(1).pageSize(8).build();
        });
        mvc.perform(get("/api/v1/invoices")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0]").value(org.hamcrest.Matchers.hasKey("id")))
                .andExpect(jsonPath("$.data.items[0].id").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.items[0].seller_name").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.items[0].error_code").value("IMAGE_DOWNLOAD_FAILED"));
    }

    /** Rejects unknown and unfinished states and invalid page bounds before any invoice service call. */
    @Test
    void rejectsInvalidFilters() throws Exception {
        log.info("Testing invalid invoice outcome filters");
        for (String state : List.of("UNKNOWN", "UPLOADED", "PROCESSING", "PENDING_FAILURE", "UPLOADED_FAILED")) {
            mvc.perform(get("/api/v1/invoices").param("current_state", state))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(1));
        }
        for (String size : List.of("0", "101", "abc")) {
            mvc.perform(get("/api/v1/invoices").param("page_size", size)).andExpect(status().isBadRequest());
        }
        mvc.perform(get("/api/v1/invoices").param("page", "0")).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
}
