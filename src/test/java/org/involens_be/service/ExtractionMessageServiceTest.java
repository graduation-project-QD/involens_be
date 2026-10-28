package org.involens_be.service;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.minio.MinioClient;
import lombok.extern.slf4j.Slf4j;
import org.involens_be.common.CommonStatus;
import org.involens_be.common.DocumentState;
import org.involens_be.common.UploadStatus;
import org.involens_be.entity.DocumentEntity;
import org.involens_be.repository.DocumentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@Slf4j
@ExtendWith(MockitoExtension.class)
class ExtractionMessageServiceTest {

    @Mock
    private RabbitTemplate rabbitTemplate;

    @Mock
    private DocumentRepository documentRepository;

    private ExtractionMessageService extractionMessageService;
    private DocumentEntity document;
    @Mock private DocumentStatusNotificationService statusNotifications;

    /** Prepares an uploaded document and extraction routing without contacting RabbitMQ or the database. */
    @BeforeEach
    void setUp() {
        log.info("Setting up extraction dispatch test");
        extractionMessageService = new ExtractionMessageService(rabbitTemplate, new Gson(), documentRepository,
                statusNotifications,
                org.mockito.Mockito.mock(AiExtractionResultService.class));
        ReflectionTestUtils.setField(extractionMessageService, "enabled", true);
        ReflectionTestUtils.setField(extractionMessageService, "extractExchange", "test-extraction");
        ReflectionTestUtils.setField(extractionMessageService, "extractRoutingKey", "test-extract");
        ReflectionTestUtils.setField(extractionMessageService, "bucket", "involens-documents");
        ReflectionTestUtils.setField(extractionMessageService, "publicMinioClient",
                new org.involens_be.config.AppConfig().publicMinioClient(
                        "http://localhost:9000", "test-access", "test-secret", "us-east-1"));
        document = DocumentEntity.builder()
                .id(UUID.randomUUID())
                .originalFilename("receipt.jpg")
                .objectKey("2026/10/receipt.jpg")
                .status(CommonStatus.ACTIVE)
                .uploadStatus(UploadStatus.UPLOADED)
                .currentState(DocumentState.UPLOADED)
                .build();
        log.info("Extraction dispatch test setup completed. documentId={}", document.getId());
    }

    /** Verifies the AI payload is published before the guarded state update while the upload response snapshot stays unchanged. */
    @ParameterizedTest
    @ValueSource(strings = {"jpg", "pdf"})
    void sendExtractPublishesDocumentBeforeMarkingProcessing(String extension) {
        log.info("Testing processing transition after successful extraction dispatch");
        document.setObjectKey("2026/10/receipt." + extension);
        when(documentRepository.updateDispatchStateIfMatching(
                document.getId(), DocumentState.UPLOADED, DocumentState.PROCESSING, null, null)).thenReturn(1);

        extractionMessageService.sendExtract(document);

        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        InOrder dispatchOrder = inOrder(rabbitTemplate, documentRepository, statusNotifications);
        dispatchOrder.verify(rabbitTemplate).send(
                eq("test-extraction"), eq("test-extract"), messageCaptor.capture());
        dispatchOrder.verify(documentRepository).updateDispatchStateIfMatching(
                document.getId(), DocumentState.UPLOADED, DocumentState.PROCESSING, null, null);
        dispatchOrder.verify(statusNotifications).notifyCommitted(org.mockito.ArgumentMatchers.argThat(
                event -> event.payload().documentId().equals(document.getId())
                        && event.payload().currentState() == DocumentState.PROCESSING));
        verifyNoMoreInteractions(rabbitTemplate, documentRepository);

        Message message = messageCaptor.getValue();
        assertEquals(MessageProperties.CONTENT_TYPE_JSON, message.getMessageProperties().getContentType());
        assertEquals(StandardCharsets.UTF_8.name(), message.getMessageProperties().getContentEncoding());
        JsonObject payload = JsonParser.parseString(
                new String(message.getBody(), StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals(document.getId().toString(), payload.get("id").getAsString());
        assertEquals(2, payload.size());
        assertFalse(payload.has("fileName"));
        String url = payload.get("url").getAsString();
        assertTrue(url.startsWith("http://localhost:9000/involens-documents/" + document.getObjectKey() + "?"));
        assertTrue(url.contains("X-Amz-Expires=3600"));
        assertTrue(url.contains("X-Amz-Signature="));
        assertEquals(CommonStatus.ACTIVE, document.getStatus());
        assertEquals(UploadStatus.UPLOADED, document.getUploadStatus());
        assertEquals(DocumentState.UPLOADED, document.getCurrentState());
        log.info("Successful extraction dispatch transition test completed");
    }

    /** Verifies signing failures exhaust dispatch attempts without sending an unusable request to AI. */
    @Test
    void sendExtractRecordsFailureWhenUrlSigningFails() throws Exception {
        log.info("Testing extraction dispatch when download URL signing fails");
        MinioClient signer = org.mockito.Mockito.mock(MinioClient.class);
        ReflectionTestUtils.setField(extractionMessageService, "publicMinioClient", signer);
        when(signer.getPresignedObjectUrl(any())).thenThrow(new java.io.IOException("signing failure"));

        assertDoesNotThrow(() -> extractionMessageService.sendExtract(document));

        verify(signer, times(4)).getPresignedObjectUrl(any());
        verifyNoInteractions(rabbitTemplate);
        verify(documentRepository).updateDispatchStateIfMatching(
                document.getId(), DocumentState.UPLOADED, DocumentState.PENDING_FAILURE,
                ExtractionMessageService.DISPATCH_FAILED_CODE, ExtractionMessageService.DISPATCH_FAILED_MESSAGE);
        log.info("URL signing failure dispatch test completed");
    }

    /** Verifies a transient signing failure is retried before publishing a usable download URL. */
    @Test
    void publishExtractRetriesUrlSigningFailure() throws Exception {
        log.info("Testing extraction dispatch recovery after a download URL signing failure");
        MinioClient signer = org.mockito.Mockito.mock(MinioClient.class);
        ReflectionTestUtils.setField(extractionMessageService, "publicMinioClient", signer);
        String url = "http://localhost:9000/involens-documents/receipt.jpg?signature=test";
        when(signer.getPresignedObjectUrl(any()))
                .thenThrow(new java.io.IOException("temporary signing failure")).thenReturn(url);

        assertTrue(extractionMessageService.publishExtract(document));

        verify(signer, times(2)).getPresignedObjectUrl(any());
        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(rabbitTemplate).send(eq("test-extraction"), eq("test-extract"), messageCaptor.capture());
        JsonObject payload = JsonParser.parseString(
                new String(messageCaptor.getValue().getBody(), StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals(url, payload.get("url").getAsString());
        verifyNoInteractions(documentRepository);
        log.info("URL signing retry recovery test completed");
    }

    /** Verifies a temporary publish failure is retried and processing is recorded only after the successful retry. */
    @Test
    void sendExtractMarksProcessingAfterSuccessfulRetry() {
        log.info("Testing processing transition after extraction dispatch retry");
        doThrow(new AmqpException("temporary RabbitMQ failure"))
                .doNothing()
                .when(rabbitTemplate).send(eq("test-extraction"), eq("test-extract"), any(Message.class));
        when(documentRepository.updateDispatchStateIfMatching(
                document.getId(), DocumentState.UPLOADED, DocumentState.PROCESSING, null, null)).thenReturn(1);

        extractionMessageService.sendExtract(document);

        InOrder dispatchOrder = inOrder(rabbitTemplate, documentRepository);
        dispatchOrder.verify(rabbitTemplate, times(2)).send(
                eq("test-extraction"), eq("test-extract"), any(Message.class));
        dispatchOrder.verify(documentRepository).updateDispatchStateIfMatching(
                document.getId(), DocumentState.UPLOADED, DocumentState.PROCESSING, null, null);
        verifyNoMoreInteractions(rabbitTemplate, documentRepository);
        log.info("Extraction retry transition test completed successfully");
    }

    /** Verifies that exhausting all four publish attempts records pending failure. */
    @Test
    void sendExtractDoesNotMarkProcessingWhenAllPublishAttemptsFail() {
        log.info("Testing document state preservation after extraction dispatch exhaustion");
        doThrow(new AmqpException("RabbitMQ unavailable"))
                .when(rabbitTemplate).send(eq("test-extraction"), eq("test-extract"), any(Message.class));

        assertDoesNotThrow(() -> extractionMessageService.sendExtract(document));

        verify(rabbitTemplate, times(4)).send(
                eq("test-extraction"), eq("test-extract"), any(Message.class));
        verify(documentRepository).updateDispatchStateIfMatching(
                document.getId(), DocumentState.UPLOADED, DocumentState.PENDING_FAILURE,
                "EXTRACTION_DISPATCH_FAILED", "Không thể gửi tài liệu sang AI trích xuất");
        verifyNoMoreInteractions(rabbitTemplate);
        assertEquals(DocumentState.UPLOADED, document.getCurrentState());
        log.info("Extraction dispatch exhaustion test completed successfully");
    }

    /** Verifies disabled RabbitMQ records pending failure without publishing. */
    @Test
    void sendExtractSkipsDisabledRabbitMq() {
        log.info("Testing disabled extraction dispatch");
        ReflectionTestUtils.setField(extractionMessageService, "enabled", false);

        extractionMessageService.sendExtract(document);

        verifyNoInteractions(rabbitTemplate);
        verify(documentRepository).updateDispatchStateIfMatching(
                document.getId(), DocumentState.UPLOADED, DocumentState.PENDING_FAILURE,
                "EXTRACTION_DISPATCH_FAILED", "Không thể gửi tài liệu sang AI trích xuất");
        log.info("Disabled extraction dispatch test completed successfully");
    }

    /** Verifies an empty exchange records pending failure without publishing. */
    @Test
    void sendExtractSkipsMissingExchange() {
        log.info("Testing extraction dispatch without an exchange");
        ReflectionTestUtils.setField(extractionMessageService, "extractExchange", "");

        extractionMessageService.sendExtract(document);

        verifyNoInteractions(rabbitTemplate);
        verify(documentRepository).updateDispatchStateIfMatching(
                document.getId(), DocumentState.UPLOADED, DocumentState.PENDING_FAILURE,
                "EXTRACTION_DISPATCH_FAILED", "Không thể gửi tài liệu sang AI trích xuất");
        log.info("Missing extraction exchange test completed successfully");
    }

    /** Verifies a blank routing key records pending failure without publishing. */
    @Test
    void sendExtractSkipsMissingRoutingKey() {
        log.info("Testing extraction dispatch without a routing key");
        ReflectionTestUtils.setField(extractionMessageService, "extractRoutingKey", "   ");

        extractionMessageService.sendExtract(document);

        verifyNoInteractions(rabbitTemplate);
        verify(documentRepository).updateDispatchStateIfMatching(
                document.getId(), DocumentState.UPLOADED, DocumentState.PENDING_FAILURE,
                "EXTRACTION_DISPATCH_FAILED", "Không thể gửi tài liệu sang AI trích xuất");
        log.info("Missing extraction routing key test completed successfully");
    }

    /** Verifies database failure after a successful publish is contained and never resends the AI request. */
    @Test
    void sendExtractDoesNotRepublishWhenProcessingUpdateFails() {
        log.info("Testing extraction dispatch when the processing update fails");
        when(documentRepository.updateDispatchStateIfMatching(
                document.getId(), DocumentState.UPLOADED, DocumentState.PROCESSING, null, null))
                .thenThrow(new IllegalStateException("database unavailable"));

        assertDoesNotThrow(() -> extractionMessageService.sendExtract(document));

        verify(rabbitTemplate).send(eq("test-extraction"), eq("test-extract"), any(Message.class));
        verify(documentRepository).updateDispatchStateIfMatching(
                document.getId(), DocumentState.UPLOADED, DocumentState.PROCESSING, null, null);
        verifyNoMoreInteractions(rabbitTemplate, documentRepository);
        assertEquals(DocumentState.UPLOADED, document.getCurrentState());
        log.info("Failed processing update containment test completed successfully");
    }

    /** Verifies a document that no longer matches UPLOADED is not overwritten or sent to AI again. */
    @Test
    void sendExtractDoesNotRepublishWhenProcessingUpdateMatchesNoDocument() {
        log.info("Testing extraction dispatch when the document state has already advanced");
        when(documentRepository.updateDispatchStateIfMatching(
                document.getId(), DocumentState.UPLOADED, DocumentState.PROCESSING, null, null)).thenReturn(0);

        assertDoesNotThrow(() -> extractionMessageService.sendExtract(document));

        verify(rabbitTemplate).send(eq("test-extraction"), eq("test-extract"), any(Message.class));
        verify(documentRepository).updateDispatchStateIfMatching(
                document.getId(), DocumentState.UPLOADED, DocumentState.PROCESSING, null, null);
        verifyNoMoreInteractions(rabbitTemplate, documentRepository);
        log.info("Already advanced document state test completed successfully");
    }

    /** Verifies retry callers can publish successfully without writing the document state in a separate transaction. */
    @Test
    void publishExtractReturnsSuccessWithoutUpdatingDocument() {
        log.info("Testing publish-only extraction dispatch without a database update");

        assertTrue(extractionMessageService.publishExtract(document));

        verify(rabbitTemplate).send(eq("test-extraction"), eq("test-extract"), any(Message.class));
        verifyNoInteractions(documentRepository);
        verifyNoMoreInteractions(rabbitTemplate);
        assertEquals(DocumentState.UPLOADED, document.getCurrentState());
        log.info("Publish-only extraction dispatch test completed successfully");
    }

    /** Verifies the fourth publish attempt can succeed while the caller retains responsibility for state changes. */
    @Test
    void publishExtractReturnsSuccessOnLastAttemptWithoutUpdatingDocument() {
        log.info("Testing publish-only extraction dispatch success on the fourth attempt");
        doThrow(new AmqpException("first RabbitMQ failure"))
                .doThrow(new AmqpException("second RabbitMQ failure"))
                .doThrow(new AmqpException("third RabbitMQ failure"))
                .doNothing()
                .when(rabbitTemplate).send(eq("test-extraction"), eq("test-extract"), any(Message.class));

        assertTrue(extractionMessageService.publishExtract(document));

        verify(rabbitTemplate, times(4)).send(
                eq("test-extraction"), eq("test-extract"), any(Message.class));
        verifyNoInteractions(documentRepository);
        verifyNoMoreInteractions(rabbitTemplate);
        log.info("Publish-only final attempt success test completed successfully");
    }

    /** Verifies exhausted publish attempts return failure so the retry caller can preserve the document state. */
    @Test
    void publishExtractReturnsFailureAfterAllAttemptsFail() {
        log.info("Testing publish-only extraction dispatch failure after all attempts");
        doThrow(new AmqpException("RabbitMQ unavailable"))
                .when(rabbitTemplate).send(eq("test-extraction"), eq("test-extract"), any(Message.class));

        assertFalse(extractionMessageService.publishExtract(document));

        verify(rabbitTemplate, times(4)).send(
                eq("test-extraction"), eq("test-extract"), any(Message.class));
        verifyNoInteractions(documentRepository);
        verifyNoMoreInteractions(rabbitTemplate);
        log.info("Publish-only attempt exhaustion test completed successfully");
    }

    /** Verifies disabled or incomplete RabbitMQ routing reports failure without publishing or changing database state. */
    @Test
    void publishExtractReturnsFailureWhenRabbitMqIsUnavailableOrUnconfigured() {
        log.info("Testing publish-only extraction dispatch configuration failures");
        ReflectionTestUtils.setField(extractionMessageService, "enabled", false);
        assertFalse(extractionMessageService.publishExtract(document));

        ReflectionTestUtils.setField(extractionMessageService, "enabled", true);
        ReflectionTestUtils.setField(extractionMessageService, "extractExchange", "");
        assertFalse(extractionMessageService.publishExtract(document));

        ReflectionTestUtils.setField(extractionMessageService, "extractExchange", "test-extraction");
        ReflectionTestUtils.setField(extractionMessageService, "extractRoutingKey", "   ");
        assertFalse(extractionMessageService.publishExtract(document));

        verifyNoInteractions(rabbitTemplate, documentRepository);
        log.info("Publish-only extraction configuration failures test completed successfully");
    }
}
