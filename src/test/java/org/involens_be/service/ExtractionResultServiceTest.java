package org.involens_be.service;

import org.involens_be.common.AiStatus;
import org.involens_be.common.CommonStatus;
import org.involens_be.common.UserRole;
import org.involens_be.entity.DocumentEntity;
import org.involens_be.entity.ExtractionResultEntity;
import org.involens_be.entity.InvoiceEntity;
import org.involens_be.entity.ReviewTaskEntity;
import org.involens_be.exception.DocumentNotFoundException;
import org.involens_be.exception.ExtractionResultNotFoundException;
import org.involens_be.exception.InvalidInvoiceUpdateException;
import org.involens_be.mapper.DocumentMapper;
import org.involens_be.repository.DocumentRepository;
import org.involens_be.repository.ExtractionResultRepository;
import org.involens_be.repository.UserRepository;
import org.involens_be.repository.InvoiceRepository;
import org.involens_be.repository.ReviewTaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.List;
import java.math.BigDecimal;
import java.time.LocalDate;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.involens_be.dto.request.UpdateInvoiceExtractionRequest;
import org.involens_be.common.DocumentState;
import org.involens_be.common.ReviewStatus;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ExtractionResultServiceTest {
    @Mock private DocumentRepository documents;
    @Mock private ExtractionResultRepository extractionResults;
    @Mock private InvoiceRepository invoices;
    @Mock private ReviewTaskRepository reviewTasks;
    @Mock private org.involens_be.repository.InvoiceItemRepository invoiceItems;
    private DocumentService service;

    /** Creates an isolated extraction-result service with mocked persistence dependencies. */
    @BeforeEach
    void setUp() {
        org.slf4j.LoggerFactory.getLogger(getClass()).info("Preparing extraction-result service tests");
        service = new DocumentService(documents,
                mock(UserRepository.class), Mappers.getMapper(DocumentMapper.class), extractionResults,
                invoices, reviewTasks, invoiceItems);
        org.slf4j.LoggerFactory.getLogger(getClass()).info("Extraction-result service test setup completed");
    }

    /** Returns AI failure details as normal result data and exposes database timestamps as UTC. */
    @Test
    void returnsAiFailureAsResultData() {
        UUID documentId = UUID.randomUUID();
        UUID resultId = UUID.randomUUID();
        when(documents.findByIdAndUploadedByIdAndStatus(documentId, 12L, CommonStatus.ACTIVE))
                .thenReturn(Optional.of(DocumentEntity.builder().id(documentId).build()));
        when(extractionResults.findByDocumentId(documentId)).thenReturn(Optional.of(
                ExtractionResultEntity.builder().id(resultId).documentId(documentId).aiStatus(AiStatus.FAILED)
                        .retryCount(2).humanReviewRequired(true).rawJson(Map.of("engine", "invoice-ocr-v2"))
                        .errorCode("AI_EXTRACTION_FAILED").errorMessage("AI failed")
                        .processingStartedAt(LocalDateTime.parse("2026-09-10T08:15:10"))
                        .processingFinishedAt(LocalDateTime.parse("2026-09-10T08:16:02")).build()));

        var response = service.getExtractionResult(documentId, 12L, UserRole.EMPLOYEE);

        assertEquals(resultId, response.getId());
        assertEquals(AiStatus.FAILED, response.getAiStatus());
        assertNull(response.getSellerName());
        assertNull(response.getItems());
        assertEquals("AI_EXTRACTION_FAILED", response.getErrorCode());
        assertEquals(OffsetDateTime.parse("2026-09-10T08:15:10Z"), response.getProcessingStartedAt());
    }

    /** Hides extraction data when the associated document is not visible to the authenticated user. */
    @Test
    void inaccessibleDocumentIsNotFoundBeforeExtractionLookup() {
        UUID documentId = UUID.randomUUID();
        when(documents.findByIdAndUploadedByIdAndStatus(documentId, 12L, CommonStatus.ACTIVE))
                .thenReturn(Optional.empty());

        assertThrows(DocumentNotFoundException.class,
                () -> service.getExtractionResult(documentId, 12L, UserRole.EMPLOYEE));
        verifyNoInteractions(extractionResults);
    }

    /** Returns the dedicated business error when an accessible document has no extraction result. */
    @Test
    void missingExtractionResultUsesDedicatedError() {
        UUID documentId = UUID.randomUUID();
        when(documents.findByIdAndUploadedByIdAndStatus(documentId, 12L, CommonStatus.ACTIVE))
                .thenReturn(Optional.of(DocumentEntity.builder().id(documentId).build()));
        when(extractionResults.findByDocumentId(documentId)).thenReturn(Optional.empty());

        assertThrows(ExtractionResultNotFoundException.class,
                () -> service.getExtractionResult(documentId, 12L, UserRole.EMPLOYEE));
    }

    /** Allows a manager to load extraction data for an active document uploaded by another user. */
    @Test
    void managerCanLoadAnyActiveDocumentExtractionResult() {
        UUID documentId = UUID.randomUUID();
        when(documents.findByIdAndStatus(documentId, CommonStatus.ACTIVE))
                .thenReturn(Optional.of(DocumentEntity.builder().id(documentId).uploadedById(44L).build()));
        when(extractionResults.findByDocumentId(documentId)).thenReturn(Optional.of(
                ExtractionResultEntity.builder().id(UUID.randomUUID()).documentId(documentId)
                        .aiStatus(AiStatus.SUCCESS).build()));

        var response = service.getExtractionResult(documentId, 12L, UserRole.MANAGER);

        assertEquals(documentId, response.getDocumentId());
        verify(documents, never()).findByIdAndUploadedByIdAndStatus(any(), anyLong(), any());
    }

    /** Confirms an existing AI-produced invoice from an empty object and completes its review workflow. */
    @Test
    void emptyObjectConfirmsExistingInvoice() throws Exception {
        UUID documentId = UUID.randomUUID();
        UUID extractionId = UUID.randomUUID();
        DocumentEntity document = DocumentEntity.builder().id(documentId)
                .currentState(DocumentState.REVIEW_REQUIRED).build();
        ExtractionResultEntity extraction = ExtractionResultEntity.builder().id(extractionId)
                .documentId(documentId).aiStatus(AiStatus.REVIEW_REQUIRED).humanReviewRequired(true).build();
        InvoiceEntity invoice = InvoiceEntity.builder().id(UUID.randomUUID()).documentId(documentId)
                .extractionResultId(extractionId).sellerName("AI Seller").invoiceNumber("AI-1")
                .invoiceDate(LocalDate.now().minusDays(1)).totalAmount(BigDecimal.TEN).currency("VND").build();
        ReviewTaskEntity task = ReviewTaskEntity.builder().id(UUID.randomUUID()).extractionResultId(extractionId)
                .status(ReviewStatus.PENDING).build();
        when(documents.findByIdAndUploadedByIdAndStatus(documentId, 12L, CommonStatus.ACTIVE))
                .thenReturn(Optional.of(document));
        when(extractionResults.findByDocumentId(documentId)).thenReturn(Optional.of(extraction));
        when(invoices.findByExtractionResultId(extractionId)).thenReturn(Optional.of(invoice));
        when(reviewTasks.findAllByExtractionResultId(extractionId)).thenReturn(List.of(task));
        when(invoices.save(invoice)).thenReturn(invoice);
        UpdateInvoiceExtractionRequest request = new ObjectMapper().readValue(
                "{}", UpdateInvoiceExtractionRequest.class);

        var response = service.updateExtractionResult(documentId, request, 12L, UserRole.EMPLOYEE);

        assertAll(
                () -> assertEquals("AI Seller", response.getInvoice().getSellerName()),
                () -> assertEquals(AiStatus.SUCCESS, extraction.getAiStatus()),
                () -> assertFalse(extraction.getHumanReviewRequired()),
                () -> assertEquals(DocumentState.EXTRACT_SUCCESS, document.getCurrentState()),
                () -> assertEquals(ReviewStatus.COMPLETED, task.getStatus()),
                () -> assertEquals(12L, invoice.getApprovedById()));
        assertNotNull(task.getCompletedAt());
        assertNotNull(invoice.getApprovedAt());
        assertEquals(List.of(), response.getInvoice().getItems());
        verify(invoiceItems, never()).deleteAllByInvoiceId(any());
    }

    /** Creates a missing invoice from complete reviewed fields before marking extraction as successful. */
    @Test
    void completeRequestCreatesMissingInvoice() throws Exception {
        UUID documentId = UUID.randomUUID();
        UUID extractionId = UUID.randomUUID();
        DocumentEntity document = DocumentEntity.builder().id(documentId)
                .currentState(DocumentState.REVIEW_REQUIRED).build();
        ExtractionResultEntity extraction = ExtractionResultEntity.builder().id(extractionId)
                .documentId(documentId).aiStatus(AiStatus.REVIEW_REQUIRED).humanReviewRequired(true).build();
        when(documents.findByIdAndStatus(documentId, CommonStatus.ACTIVE)).thenReturn(Optional.of(document));
        when(extractionResults.findByDocumentId(documentId)).thenReturn(Optional.of(extraction));
        when(invoices.findByExtractionResultId(extractionId)).thenReturn(Optional.empty());
        when(reviewTasks.findAllByExtractionResultId(extractionId)).thenReturn(List.of());
        when(invoices.save(any())).thenAnswer(invocation -> {
            InvoiceEntity saved = invocation.getArgument(0);
            saved.setId(UUID.randomUUID());
            return saved;
        });
        when(invoiceItems.saveAll(any())).thenAnswer(invocation -> {
            List<org.involens_be.entity.InvoiceItemEntity> lines = invocation.getArgument(0);
            when(invoiceItems.findAllByInvoiceId(lines.getFirst().getInvoiceId())).thenReturn(lines);
            return lines;
        });
        String date = LocalDate.now(java.time.ZoneId.of("Asia/Ho_Chi_Minh")).minusDays(1).toString();
        UpdateInvoiceExtractionRequest request = new ObjectMapper().findAndRegisterModules().readValue("""
                {"sellerName":"Reviewed Seller","invoiceNumber":"INV-2","invoiceDate":"%s",
                 "totalAmount":125.50,"currency":"ANY","sellerAddress":null,"taxCode":"free-form",
                 "items":[{"item_name":"Rice","quantity":1.5,"unit_price":10,"line_total":15}]}
                """.formatted(date), UpdateInvoiceExtractionRequest.class);

        var response = service.updateExtractionResult(documentId, request, 99L, UserRole.MANAGER);

        assertAll(
                () -> assertEquals("Reviewed Seller", response.getInvoice().getSellerName()),
                () -> assertEquals(0, new BigDecimal("125.50")
                        .compareTo(response.getInvoice().getTotalAmount())),
                () -> assertEquals("ANY", response.getInvoice().getCurrency()),
                () -> assertNull(response.getInvoice().getSellerAddress()),
                () -> assertEquals("free-form", response.getInvoice().getTaxCode()));
        verify(invoices).save(argThat(saved -> saved.getDocumentId().equals(documentId)
                && saved.getExtractionResultId().equals(extractionId)));
        assertEquals(1.5f, response.getInvoice().getItems().getFirst().getQuantity());
        assertEquals("Rice", response.getExtractionResult().getItems().getFirst().getItemName());
        verify(invoiceItems).deleteAllByInvoiceId(response.getInvoice().getId());
    }

    /** Returns corrected invoice fields and lines instead of the original AI JSON. */
    @Test
    void getReturnsPersistedInvoiceAndSnakeCaseItems() throws Exception {
        org.slf4j.LoggerFactory.getLogger(getClass()).info("Testing extraction invoice response");
        UUID documentId = UUID.randomUUID();
        UUID extractionId = UUID.randomUUID();
        UUID invoiceId = UUID.randomUUID();
        when(documents.findByIdAndStatus(documentId, CommonStatus.ACTIVE))
                .thenReturn(Optional.of(DocumentEntity.builder().id(documentId).build()));
        when(extractionResults.findByDocumentId(documentId)).thenReturn(Optional.of(
                ExtractionResultEntity.builder().id(extractionId).documentId(documentId).build()));
        when(invoices.findByExtractionResultId(extractionId)).thenReturn(Optional.of(
                InvoiceEntity.builder().id(invoiceId).sellerName("Reviewed seller").build()));
        when(invoiceItems.findAllByInvoiceId(invoiceId)).thenReturn(List.of(
                org.involens_be.entity.InvoiceItemEntity.builder().invoiceId(invoiceId).itemName("Rice")
                        .quantity(1.5f).unitPrice(BigDecimal.TEN).lineTotal(new BigDecimal("15")).build()));
        var response = service.getExtractionResult(documentId, 99L, UserRole.MANAGER);
        var json = new ObjectMapper().findAndRegisterModules().valueToTree(response);
        assertEquals("Reviewed seller", json.get("seller_name").asText());
        assertEquals("Rice", json.get("items").get(0).get("item_name").asText());
        assertEquals(1.5, json.get("items").get(0).get("quantity").asDouble());
        assertTrue(json.get("items").get(0).has("unit_price"));
        assertTrue(json.get("raw_json").isNull());
        assertTrue(json.get("currency").isNull());
        assertEquals(0, json.get("warnings").size());
        assertEquals(0, json.get("polygons").size());
        var gson = new com.google.gson.GsonBuilder().excludeFieldsWithoutExposeAnnotation().create();
        assertTrue(gson.toJson(response).contains("\"item_name\""));
        assertTrue(gson.toJson(response).contains("\"polygons\":{}"));
    }

    /** Exposes original recognition evidence alongside reviewed currency without changing the saved AI response. */
    @Test
    void returnsCurrencyWarningsPolygonsAndRawJson() throws Exception {
        org.slf4j.LoggerFactory.getLogger(getClass()).info("Testing AI evidence in extraction response");
        Map<String, Object> raw = new ObjectMapper().readValue("""
                {"status":0,"error":null,"data":{
                  "currency":"VND","warnings":[{"code":"LOW_CONFIDENCE","target":"line_items[0].item_name"}],
                  "fields":{
                    "seller":{"value":"Original seller","status":"found","evidence":[
                      {"polygon":[[1,2],[3,4],[5,6]]},{"polygon":[[7,8],[9,10],[11,12]]}]},
                    "address":{"value":null,"status":"absent","evidence":[]}},
                  "line_items":[{"index":42,"item_name":{"evidence":[{"polygon":[[13,14],[15,16]]}]},
                    "unit_price":{"status":"absent","value":null,"evidence":[]}}],
                  "image":{"width":576,"height":1024,"rotation_applied":0},
                  "meta":{"schema_version":"1.0"}}}
                """, new com.fasterxml.jackson.core.type.TypeReference<>() { });
        var mapper = Mappers.getMapper(DocumentMapper.class);
        var response = mapper.toExtractionResultResponse(
                ExtractionResultEntity.builder().rawJson(raw).build(),
                InvoiceEntity.builder().currency("USD").sellerName("Reviewed seller").build(), List.of());
        assertEquals("USD", response.getCurrency());
        assertEquals("Reviewed seller", response.getSellerName());
        assertEquals(raw, response.getRawJson());
        assertEquals(1, response.getWarnings().size());
        assertEquals(2, response.getPolygons().get("fields.seller").size());
        assertEquals(List.of(List.of(13, 14), List.of(15, 16)),
                response.getPolygons().get("line_items[0].item_name").getFirst());
        assertFalse(response.getPolygons().containsKey("fields.address"));
        assertFalse(response.getPolygons().containsKey("line_items[42].item_name"));
        var jsonMapper = new ObjectMapper().findAndRegisterModules();
        var json = jsonMapper.valueToTree(response);
        assertEquals("USD", json.get("currency").asText());
        assertEquals("LOW_CONFIDENCE", json.get("warnings").get(0).get("code").asText());
        assertEquals(jsonMapper.valueToTree(raw), json.get("raw_json"));
        assertEquals(2, json.get("polygons").get("fields.seller").size());
        var gson = new com.google.gson.GsonBuilder().excludeFieldsWithoutExposeAnnotation().serializeNulls().create();
        var gsonJson = com.google.gson.JsonParser.parseString(gson.toJson(response)).getAsJsonObject();
        assertTrue(gsonJson.has("raw_json"));
        assertEquals(1, gsonJson.getAsJsonArray("warnings").size());
    }

    /** Missing, legacy and failed AI payloads return empty highlights and warnings while preserving raw data. */
    @Test
    void missingEvidenceReturnsEmptyCollections() {
        org.slf4j.LoggerFactory.getLogger(getClass()).info("Testing extraction responses without AI evidence");
        var mapper = Mappers.getMapper(DocumentMapper.class);
        var missing = mapper.toExtractionResultResponse(ExtractionResultEntity.builder().build(), null, null);
        assertNull(missing.getRawJson());
        assertNull(missing.getCurrency());
        assertTrue(missing.getWarnings().isEmpty());
        assertTrue(missing.getPolygons().isEmpty());
        for (Map<String, Object> raw : List.<Map<String, Object>>of(
                Map.of("engine", "legacy"), Map.of("status", 1, "data", Map.of("id", "document-id")))) {
            var response = mapper.toExtractionResultResponse(
                    ExtractionResultEntity.builder().rawJson(raw).build(), null, null);
            assertEquals(raw, response.getRawJson());
            assertTrue(response.getWarnings().isEmpty());
            assertTrue(response.getPolygons().isEmpty());
        }
    }

    /** Accepts an empty replacement and rejects missing or invalid reviewed line values. */
    @Test
    void validatesItemReplacement() throws Exception {
        org.slf4j.LoggerFactory.getLogger(getClass()).info("Testing item replacement validation");
        var mapper = new ObjectMapper();
        var empty = mapper.readValue("{\"items\":[]}", UpdateInvoiceExtractionRequest.class);
        List<?> result = org.springframework.test.util.ReflectionTestUtils.invokeMethod(service, "validateInvoiceItems", empty);
        assertEquals(List.of(), result);
        for (String body : List.of("{\"items\":null}", "{\"items\":[null]}",
                "{\"items\":[{}]}",
                "{\"items\":[{\"item_name\":\"Rice\",\"quantity\":0,\"unit_price\":10,\"line_total\":0}]}",
                "{\"items\":[{\"item_name\":\"Rice\",\"quantity\":1.5,\"unit_price\":-1,\"line_total\":0}]}")) {
            var request = mapper.readValue(body, UpdateInvoiceExtractionRequest.class);
            assertThrows(InvalidInvoiceUpdateException.class, () ->
                    org.springframework.test.util.ReflectionTestUtils.invokeMethod(service, "validateInvoiceItems", request));
        }
    }

    /** Rejects an empty object when no invoice exists because required creation data is unavailable. */
    @Test
    void emptyObjectCannotCreateMissingInvoice() throws Exception {
        UUID documentId = UUID.randomUUID();
        UUID extractionId = UUID.randomUUID();
        when(documents.findByIdAndUploadedByIdAndStatus(documentId, 12L, CommonStatus.ACTIVE))
                .thenReturn(Optional.of(DocumentEntity.builder().id(documentId).build()));
        when(extractionResults.findByDocumentId(documentId)).thenReturn(Optional.of(
                ExtractionResultEntity.builder().id(extractionId).documentId(documentId).build()));
        when(invoices.findByExtractionResultId(extractionId)).thenReturn(Optional.empty());
        UpdateInvoiceExtractionRequest request = new ObjectMapper().readValue(
                "{}", UpdateInvoiceExtractionRequest.class);

        assertThrows(InvalidInvoiceUpdateException.class,
                () -> service.updateExtractionResult(documentId, request, 12L, UserRole.EMPLOYEE));

        verify(invoices, never()).save(any());
        verifyNoInteractions(reviewTasks);
    }
    /** Verifies omitted fields remain intact while explicit null clears nullable invoice fields. */
    @Test
    void partialRequestClearsOnlySuppliedNullableFields() throws Exception {
        org.slf4j.LoggerFactory.getLogger(getClass()).info("Testing nullable invoice PATCH fields");
        InvoiceEntity invoice = InvoiceEntity.builder().sellerName("Original seller")
                .sellerAddress("Original address").taxCode("Original tax code").build();
        UpdateInvoiceExtractionRequest request = new ObjectMapper().readValue(
                "{\"sellerAddress\":null,\"taxCode\":null}", UpdateInvoiceExtractionRequest.class);

        org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                service, "applyInvoiceUpdates", invoice, request);

        assertEquals("Original seller", invoice.getSellerName());
        assertNull(invoice.getSellerAddress());
        assertNull(invoice.getTaxCode());
    }

    /** Verifies explicit null is rejected for every database-required invoice field. */
    @Test
    void partialRequestRejectsNullRequiredFields() throws Exception {
        org.slf4j.LoggerFactory.getLogger(getClass()).info("Testing required invoice PATCH fields");
        for (String field : List.of("sellerName", "invoiceNumber", "invoiceDate", "totalAmount", "currency")) {
            UpdateInvoiceExtractionRequest request = new ObjectMapper().findAndRegisterModules().readValue(
                    "{\"" + field + "\":null}", UpdateInvoiceExtractionRequest.class);
            assertThrows(InvalidInvoiceUpdateException.class,
                    () -> org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                            service, "applyInvoiceUpdates", new InvoiceEntity(), request));
        }
    }
}
