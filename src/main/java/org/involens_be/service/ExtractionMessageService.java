package org.involens_be.service;

import com.google.gson.Gson;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.http.Method;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.involens_be.common.DocumentState;
import org.involens_be.entity.DocumentEntity;
import org.involens_be.dto.request.SendDocumentExtract;
import org.involens_be.repository.DocumentRepository;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Sends extraction requests and receives extraction results from RabbitMQ. */
@Slf4j(topic = "EXTRACTION-MESSAGE-SERVICE")
@Service
@RequiredArgsConstructor
public class ExtractionMessageService {

    private final RabbitTemplate rabbitTemplate;
    private final Gson gson;
    private final DocumentRepository documentRepository;
    private final DocumentStatusNotificationService statusNotifications;
    private final AiExtractionResultService aiExtractionResultService;
    private static final int MAX_SEND_ATTEMPTS = 4;
    public static final String DISPATCH_FAILED_CODE = "EXTRACTION_DISPATCH_FAILED";
    public static final String DISPATCH_FAILED_MESSAGE = "Không thể gửi tài liệu sang AI trích xuất";

    @Value("${app.rabbitmq.enabled:false}")
    private boolean enabled;

    @Value("${app.rabbitmq.extract.exchange:}")
    private String extractExchange;

    @Value("${app.rabbitmq.extract.routing-key:}")
    private String extractRoutingKey;

    @Value("${app.minio.bucket}")
    private String bucket;

    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.beans.factory.annotation.Qualifier("publicMinioClient")
    private MinioClient publicMinioClient;

    /**
     * Sends the uploaded document ID and signed download URL to AI, retrying failed sends up to three times.
     * Records PROCESSING on success or PENDING_FAILURE on dispatch failure without resending on a database failure.
     */
    public void sendExtract(DocumentEntity document) {
        log.info("Dispatching uploaded document for extraction. documentId={}", document.getId());
        DocumentState dispatchState = publishExtract(document)
                ? DocumentState.PROCESSING : DocumentState.PENDING_FAILURE;
        markDocumentDispatchState(document, dispatchState);
        log.info("Uploaded document extraction dispatch completed. documentId={}", document.getId());
    }

    /**
     * Publishes the document ID and an hour-long signed image or PDF URL to AI with up to four attempts.
     * Generates a fresh GET URL on each attempt, treating signing errors as dispatch failures.
     * Returns whether RabbitMQ dispatch completed without a send exception, leaving database updates to the caller.
     */
    public boolean publishExtract(DocumentEntity document) {
        log.info("Publishing extraction request. documentId={}", document.getId());
        if (!enabled || !StringUtils.hasText(extractExchange) || !StringUtils.hasText(extractRoutingKey)) {
            log.info("Extraction request is pending RabbitMQ routing configuration. documentId={}", document.getId());
            return false;
        }
        for (int attempt = 1; attempt <= MAX_SEND_ATTEMPTS; attempt++) {
            try {
                log.debug("Creating extraction download URL. documentId={}", document.getId());
                String url = publicMinioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                        .method(Method.GET).bucket(bucket).object(document.getObjectKey())
                        .expiry(1, TimeUnit.HOURS).build());
                log.debug("Extraction download URL created. documentId={}", document.getId());
                SendDocumentExtract payload = SendDocumentExtract.builder()
                        .id(document.getId())
                        .url(url)
                        .build();
                MessageProperties properties = new MessageProperties();
                properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
                properties.setContentEncoding(StandardCharsets.UTF_8.name());
                Message message = new Message(gson.toJson(payload).getBytes(StandardCharsets.UTF_8), properties);
                rabbitTemplate.send(extractExchange, extractRoutingKey, message);
                log.info("Extraction request sent. documentId={}, attempt={}", document.getId(), attempt);
            } catch (Exception exception) {
                log.error("Extraction request send failed. documentId={}, attempt={}, maxAttempts={}",
                        document.getId(), attempt, MAX_SEND_ATTEMPTS, exception);
                continue;
            }
            return true;
        }
        log.error("Extraction request abandoned after three retries. documentId={}", document.getId());
        return false;
    }

    /**
     * Persists dispatch state and its failure reason, or clears old errors, after the upload commits.
     * Preserves later document states and logs database failures without publishing the request again.
     */
    private void markDocumentDispatchState(DocumentEntity document, DocumentState dispatchState) {
        UUID documentId = document.getId();
        log.info("Updating document state after extraction dispatch. documentId={}, state={}", documentId, dispatchState);
        try {
            int updatedDocuments = documentRepository.updateDispatchStateIfMatching(
                    documentId, DocumentState.UPLOADED, dispatchState,
                    dispatchState == DocumentState.PENDING_FAILURE ? DISPATCH_FAILED_CODE : null,
                    dispatchState == DocumentState.PENDING_FAILURE ? DISPATCH_FAILED_MESSAGE : null);
            if (updatedDocuments == 0) {
                log.info("Document state update skipped: document is missing or no longer uploaded. documentId={}",
                        documentId);
                return;
            }
            statusNotifications.notifyCommitted(new DocumentStatusChangedEvent(document.getUploadedById(),
                    new org.involens_be.dto.response.DocumentStatusResponse(documentId, dispatchState,
                            dispatchState == DocumentState.PENDING_FAILURE ? DISPATCH_FAILED_CODE : null,
                            dispatchState == DocumentState.PENDING_FAILURE ? DISPATCH_FAILED_MESSAGE : null)));
            log.info("Document dispatch state saved. documentId={}, state={}", documentId, dispatchState);
        } catch (RuntimeException exception) {
            log.error("Document state update after extraction dispatch failed. documentId={}",
                    documentId, exception);
        }
    }

    /** Persists the received AI outcome before allowing the queue listener to acknowledge delivery. */
    public void receiveResult(String message) {
        log.info("Receiving extraction result message");
        try {
            aiExtractionResultService.saveResult(message);
            log.info("Extraction result handling completed");
        } catch (RuntimeException exception) {
            log.error("Extraction result handling failed", exception);
            throw exception;
        }
    }
}
