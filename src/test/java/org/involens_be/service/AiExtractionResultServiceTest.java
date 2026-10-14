package org.involens_be.service;

import com.google.gson.GsonBuilder;
import lombok.extern.slf4j.Slf4j;
import org.involens_be.common.*;
import org.involens_be.entity.*;
import org.involens_be.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Verifies queue outcomes preserve original data, map partial invoices and respect transaction boundaries. */
@Slf4j
@ExtendWith(MockitoExtension.class)
class AiExtractionResultServiceTest {
    @Mock private DocumentRepository documents;
    @Mock private ExtractionResultRepository results;
    @Mock private InvoiceRepository invoices;
    @Mock private InvoiceItemRepository items;
    @Mock private ReviewTaskRepository tasks;
    private AiExtractionResultService service;
    private DocumentEntity document;
    private static final UUID RESULT_ID = UUID.fromString("12345678-1234-1234-1234-123456789012");
    private static final UUID INVOICE_ID = UUID.fromString("12345678-1234-1234-1234-123456789013");

    /** Prepares the same exposed-field Gson contract used by the application without external services. */
    @BeforeEach
    void setUp() {
        log.info("Preparing AI result persistence test");
        service = new AiExtractionResultService(new GsonBuilder().excludeFieldsWithoutExposeAnnotation().create(),
                documents, results, invoices, items, tasks);
        document = DocumentEntity.builder().id(UUID.randomUUID()).currentState(DocumentState.PROCESSING).build();
    }

    /** Builds a complete success message with one deliberately absent line price and unknown metadata. */
    private String successMessage(boolean review, String total, String timestamp) {
        log.debug("Building AI success fixture");
        return """
                {"status":0,"error":null,"data":{
                  "id":"%s","fields":{
                    "seller":{"value":"Seller","status":"found","evidence":[{"polygon":[[1,2],[3,4]]}]},
                    "address":{"value":null,"status":"absent","evidence":[]},
                    "timestamp":{"value":"%s","status":"found","evidence":[{}]},
                    "total_cost":{"value":"%s","status":"found","evidence":[{}]}},
                  "line_items":[{"index":1,
                    "item_name":{"value":"Book","status":"found","evidence":[{}]},
                    "quantity":{"value":"1.5","status":"found","evidence":[{}]},
                    "unit_price":{"value":null,"status":"absent","evidence":[]},
                    "line_total":{"value":"9000","status":"found","evidence":[{}]}}],
                  "currency":"VND","overall_confidence":0.61,"needs_review":%s,"warnings":%s,
                  "image":{"width":576,"height":1024,"rotation_applied":0},
                  "meta":{"schema_version":"1.0","processing_ms":2140,"future_field":1234567890123456789}}}
                """.formatted(document.getId(), timestamp, total, review,
                review ? "[{\"code\":\"LOW_CONFIDENCE\",\"target\":\"fields.seller\"}]" : "[]");
    }

    /** Configures generated identifiers as the database would supply them while retaining saved entities for assertions. */
    private void prepareSuccessPersistence() {
        log.debug("Preparing persistence repository responses");
        when(documents.findByIdForExtractionUpdate(document.getId())).thenReturn(Optional.of(document));
        when(results.save(any())).thenAnswer(invocation -> {
            ExtractionResultEntity result = invocation.getArgument(0);
            result.setId(RESULT_ID);
            return result;
        });
        when(invoices.save(any())).thenAnswer(invocation -> {
            InvoiceEntity invoice = invocation.getArgument(0);
            invoice.setId(INVOICE_ID);
            return invoice;
        });
    }

    /** Confirms available fields persist while absent fields remain null and unknown raw fields retain precision. */
    @Test
    void savesSuccessAndOriginalMessage() {
        log.info("Testing AI success and raw preservation");
        prepareSuccessPersistence();
        service.saveResult(successMessage(false, "126000", "2020-08-15T09:13:16"));
        var invoiceCaptor = ArgumentCaptor.forClass(InvoiceEntity.class);
        verify(invoices).save(invoiceCaptor.capture());
        assertEquals(LocalDate.of(2020, 8, 15), invoiceCaptor.getValue().getInvoiceDate());
        assertEquals(new BigDecimal("126000.00"), invoiceCaptor.getValue().getTotalAmount());
        assertNull(invoiceCaptor.getValue().getSellerAddress());
        assertNull(invoiceCaptor.getValue().getInvoiceNumber());
        var resultCaptor = ArgumentCaptor.forClass(ExtractionResultEntity.class);
        verify(results).save(resultCaptor.capture());
        assertEquals(AiStatus.SUCCESS, resultCaptor.getValue().getAiStatus());
        assertTrue(resultCaptor.getValue().getRawJson().containsKey("error"));
        Map<?, ?> data = (Map<?, ?>) resultCaptor.getValue().getRawJson().get("data");
        Map<?, ?> meta = (Map<?, ?>) data.get("meta");
        assertEquals("1234567890123456789", meta.get("future_field").toString());
        assertEquals(DocumentState.EXTRACT_SUCCESS, document.getCurrentState());
        verify(items).saveAll(argThat(lines -> {
            InvoiceItemEntity line = lines.iterator().next();
            return line.getUnitPrice() == null && line.getQuantity().equals(1.5f)
                    && INVOICE_ID.equals(line.getInvoiceId());
        }));
        verifyNoInteractions(tasks);
    }

    /** Creates one pending review task when AI supplies warnings even though all recognized values map. */
    @Test
    void createsReviewTaskForAiWarnings() {
        log.info("Testing AI-requested review");
        prepareSuccessPersistence();
        service.saveResult(successMessage(true, "126000", "2020-08-15T09:13:16"));
        var taskCaptor = ArgumentCaptor.forClass(ReviewTaskEntity.class);
        verify(tasks).save(taskCaptor.capture());
        assertEquals(ReviewStatus.PENDING, taskCaptor.getValue().getStatus());
        assertEquals(0, taskCaptor.getValue().getPriority());
        assertTrue(taskCaptor.getValue().getReason().contains("LOW_CONFIDENCE"));
        assertEquals(DocumentState.REVIEW_REQUIRED, document.getCurrentState());
    }

    /** Keeps good fields and raw bad values while converting mapping failures into a reviewable outcome. */
    @Test
    void savesMappingFailuresForReview() {
        log.info("Testing partial mapping and error preservation");
        prepareSuccessPersistence();
        service.saveResult(successMessage(false, "not-money", "not-date"));
        var resultCaptor = ArgumentCaptor.forClass(ExtractionResultEntity.class);
        verify(results).save(resultCaptor.capture());
        var result = resultCaptor.getValue();
        assertEquals(AiStatus.REVIEW_REQUIRED, result.getAiStatus());
        assertEquals("AI_RESULT_MAPPING_FAILED", result.getErrorCode());
        assertTrue(result.getErrorMessage().contains("fields.total_cost"));
        assertTrue(result.getErrorMessage().contains("fields.timestamp"));
        assertTrue(result.getRawJson().toString().contains("not-money"));
        var invoiceCaptor = ArgumentCaptor.forClass(InvoiceEntity.class);
        verify(invoices).save(invoiceCaptor.capture());
        assertNull(invoiceCaptor.getValue().getInvoiceDate());
        assertNull(invoiceCaptor.getValue().getTotalAmount());
        assertEquals("Seller", invoiceCaptor.getValue().getSellerName());
        verify(tasks).save(any());
        assertEquals("AI_RESULT_MAPPING_FAILED", document.getErrorCode());
    }

    /** Persists an AI failure with its original error and document ID without constructing an invoice. */
    @Test
    void savesAiFailure() {
        log.info("Testing AI failure persistence");
        when(documents.findByIdForExtractionUpdate(document.getId())).thenReturn(Optional.of(document));
        service.saveResult("""
                {"status":1,"error":{"code":"IMAGE_DOWNLOAD_FAILED","message":"MinIO unavailable"},
                 "data":{"id":"%s"}}
                """.formatted(document.getId()));
        var captor = ArgumentCaptor.forClass(ExtractionResultEntity.class);
        verify(results).save(captor.capture());
        assertEquals(AiStatus.FAILED, captor.getValue().getAiStatus());
        assertEquals("IMAGE_DOWNLOAD_FAILED", document.getErrorCode());
        assertEquals(DocumentState.EXTRACT_FAILED, document.getCurrentState());
        verifyNoInteractions(invoices, items, tasks);
    }

    /** Ignores terminal outcomes so repeated queue deliveries cannot overwrite reviewed invoice data. */
    @Test
    void ignoresAlreadyCompletedDocument() {
        log.info("Testing duplicate delivery protection");
        document.setCurrentState(DocumentState.EXTRACT_SUCCESS);
        when(documents.findByIdForExtractionUpdate(document.getId())).thenReturn(Optional.of(document));
        service.saveResult(successMessage(false, "126000", "2020-08-15T09:13:16"));
        verifyNoInteractions(results, invoices, items, tasks);
        verify(documents, never()).save(any());
    }

    /** Rejects contradictory review flags before any database operation for queue retry and eventual DLT handling. */
    @Test
    void rejectsContradictoryReviewFlag() {
        log.info("Testing review flag contract validation");
        String message = successMessage(true, "126000", "2020-08-15T09:13:16")
                .replace("\"needs_review\":true", "\"needs_review\":false");
        assertThrows(IllegalArgumentException.class, () -> service.saveResult(message));
        verifyNoInteractions(documents, results, invoices, items, tasks);
    }

    /** Missing or malformed document identifiers propagate instead of creating unlinked extraction records. */
    @Test
    void rejectsUnknownOrInvalidDocument() {
        log.info("Testing document identity validation");
        assertThrows(IllegalArgumentException.class,
                () -> service.saveResult(successMessage(false, "126000", "2020-08-15T09:13:16")));
        assertThrows(RuntimeException.class, () -> service.saveResult(
                successMessage(false, "126000", "2020-08-15T09:13:16").replace(document.getId().toString(), "bad-id")));
        verifyNoInteractions(results, invoices, items, tasks);
    }

    /** Verifies Spring rolls the transaction back and propagates a database failure to the listener. */
    @Test
    void rollsBackWhenDatabaseWriteFails() {
        log.info("Testing transaction rollback on invoice persistence failure");
        when(documents.findByIdForExtractionUpdate(document.getId())).thenReturn(Optional.of(document));
        when(results.save(any())).thenReturn(ExtractionResultEntity.builder().id(RESULT_ID).build());
        doThrow(new IllegalStateException("Database unavailable")).when(invoices).save(any());
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        var status = new SimpleTransactionStatus();
        when(manager.getTransaction(any())).thenReturn(status);
        ProxyFactory factory = new ProxyFactory(service);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        AiExtractionResultService transactionalService = (AiExtractionResultService) factory.getProxy();
        assertThrows(IllegalStateException.class, () -> transactionalService.saveResult(
                successMessage(false, "126000", "2020-08-15T09:13:16")));
        verify(manager).rollback(status);
        verify(manager, never()).commit(any());
        verifyNoInteractions(items, tasks);
    }
}
