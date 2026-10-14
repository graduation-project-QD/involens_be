package org.involens_be.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.Gson;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.involens_be.common.AiStatus;
import org.involens_be.common.DocumentState;
import org.involens_be.common.ReviewStatus;
import org.involens_be.dto.request.AiExtractionResultMessage;
import org.involens_be.dto.request.AiExtractionResultMessage.*;
import org.involens_be.entity.*;
import org.involens_be.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Stores AI outcomes and recoverable mapping failures without losing the original message. */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiExtractionResultService {
    private final Gson gson;
    private final DocumentRepository documentRepository;
    private final ExtractionResultRepository extractionResultRepository;
    private final InvoiceRepository invoiceRepository;
    private final InvoiceItemRepository invoiceItemRepository;
    private final ReviewTaskRepository reviewTaskRepository;

    /** Saves one outcome atomically; invalid envelopes and database failures propagate to queue retry. */
    @Transactional
    public void saveResult(String message) {
        log.info("Starting AI result persistence");
        Map<String, Object> raw = readRawMessage(message);
        AiExtractionResultMessage outcome = gson.fromJson(message, AiExtractionResultMessage.class);
        validateOutcome(outcome);
        var data = outcome.getData();
        DocumentEntity document = documentRepository.findByIdForExtractionUpdate(data.getId())
                .orElseThrow(() -> new IllegalArgumentException("AI result document does not exist"));
        if (document.getCurrentState() != DocumentState.PROCESSING) {
            log.info("Ignoring AI result for document outside processing state. documentId={}, state={}",
                    document.getId(), document.getCurrentState());
            return;
        }
        ExtractionResultEntity result = extractionResultRepository.findByDocumentId(document.getId())
                .orElseGet(() -> ExtractionResultEntity.builder().documentId(document.getId()).build());
        result.setRawJson(raw);
        result.setProcessingFinishedAt(LocalDateTime.now(Clock.systemUTC()));
        result.setErrorCode(null);
        result.setErrorMessage(null);
        result.setOverallConfidence(null);
        if (outcome.getStatus() == 1) {
            result.setAiStatus(AiStatus.FAILED);
            result.setHumanReviewRequired(false);
            result.setErrorCode(outcome.getError().getCode());
            result.setErrorMessage(outcome.getError().getMessage());
            document.setCurrentState(DocumentState.EXTRACT_FAILED);
            extractionResultRepository.save(result);
            log.info("Stored AI extraction failure. documentId={}, code={}", document.getId(), result.getErrorCode());
        } else {
            saveSuccessfulOutcome(document, result, data);
        }
        document.setErrorCode(result.getErrorCode());
        document.setErrorMessage(result.getErrorMessage());
        documentRepository.save(document);
        log.info("AI result persisted. documentId={}, state={}", document.getId(), document.getCurrentState());
    }

    /** Reads the complete message independently of DTO fields so unknown fields and explicit nulls survive. */
    private Map<String, Object> readRawMessage(String message) {
        log.debug("Reading original AI result JSON");
        try {
            // Local reader preserves integer and decimal precision without changing application JSON configuration.
            ObjectMapper reader = new ObjectMapper()
                    .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                    .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_INTEGER_FOR_INTS);
            Map<String, Object> raw = reader.readValue(message, new TypeReference<>() { });
            if (raw == null) {
                throw new IllegalArgumentException("AI result must be a JSON object");
            }
            return raw;
        } catch (JsonProcessingException exception) {
            log.warn("AI result is not a valid JSON object", exception);
            throw new IllegalArgumentException("Invalid AI result JSON", exception);
        }
    }

    /** Rejects outcomes that cannot be linked or whose success/review envelope contradicts the agreed contract. */
    private void validateOutcome(AiExtractionResultMessage outcome) {
        log.debug("Validating AI result envelope");
        if (outcome == null || outcome.getData() == null || outcome.getData().getId() == null
                || outcome.getStatus() == null || (outcome.getStatus() != 0 && outcome.getStatus() != 1)) {
            log.warn("AI result envelope is missing a document ID or supported status");
            throw new IllegalArgumentException("AI result requires document ID and status 0 or 1");
        }
        if (outcome.getStatus() == 1) {
            if (outcome.getError() == null || outcome.getError().getCode() == null
                    || outcome.getError().getMessage() == null) {
                throw new IllegalArgumentException("Failed AI result requires error code and message");
            }
            return;
        }
        var data = outcome.getData();
        if (outcome.getError() != null || data.getFields() == null || data.getLineItems() == null
                || data.getCurrency() == null || data.getCurrency().isBlank()
                || data.getNeedsReview() == null || data.getWarnings() == null
                || data.getNeedsReview() != !data.getWarnings().isEmpty()) {
            log.warn("AI success envelope has missing fields or inconsistent review flags");
            throw new IllegalArgumentException("Invalid AI success envelope or review flag");
        }
    }

    /** Maps available invoice values, records conversion failures and creates one pending review task if needed. */
    private void saveSuccessfulOutcome(DocumentEntity document, ExtractionResultEntity result, ExtractionData data) {
        log.info("Mapping successful AI result. documentId={}", document.getId());
        List<String> errors = new ArrayList<>();
        BigDecimal confidence = data.getOverallConfidence();
        if (confidence == null || confidence.signum() < 0 || confidence.compareTo(BigDecimal.ONE) > 0) {
            errors.add("overall_confidence: expected a number between 0 and 1");
        } else {
            result.setOverallConfidence(confidence);
        }
        InvoiceEntity invoice = result.getId() == null
                ? InvoiceEntity.builder().documentId(document.getId()).build()
                : invoiceRepository.findByExtractionResultId(result.getId())
                        .orElseGet(() -> InvoiceEntity.builder().documentId(document.getId()).build());
        var fields = data.getFields();
        invoice.setSellerName(readField(fields.getSeller(), "fields.seller", errors));
        invoice.setSellerAddress(readField(fields.getAddress(), "fields.address", errors));
        invoice.setInvoiceDate(readDate(fields.getTimestamp(), errors));
        invoice.setTotalAmount(readMoney(fields.getTotalCost(), "fields.total_cost", errors));
        invoice.setCurrency(data.getCurrency());
        List<InvoiceItemEntity> items = mapItems(data.getLineItems(), errors);
        boolean review = data.getNeedsReview() || !errors.isEmpty();
        result.setHumanReviewRequired(review);
        result.setAiStatus(review ? AiStatus.REVIEW_REQUIRED : AiStatus.SUCCESS);
        if (!errors.isEmpty()) {
            result.setErrorCode("AI_RESULT_MAPPING_FAILED");
            result.setErrorMessage(String.join("; ", errors));
            log.warn("AI result requires review due to mapping failures. documentId={}, errors={}", document.getId(), errors);
        }
        ExtractionResultEntity savedResult = extractionResultRepository.save(result);
        invoice.setExtractionResultId(savedResult.getId());
        InvoiceEntity savedInvoice = invoiceRepository.save(invoice);
        invoiceItemRepository.deleteAllByInvoiceId(savedInvoice.getId());
        for (InvoiceItemEntity item : items) {
            item.setInvoiceId(savedInvoice.getId());
        }
        invoiceItemRepository.saveAll(items);
        document.setCurrentState(review ? DocumentState.REVIEW_REQUIRED : DocumentState.EXTRACT_SUCCESS);
        if (review) {
            List<String> reasons = new ArrayList<>(errors);
            for (Warning warning : data.getWarnings()) {
                reasons.add(gson.toJson(warning));
            }
            reviewTaskRepository.save(ReviewTaskEntity.builder().extractionResultId(savedResult.getId())
                    .status(ReviewStatus.PENDING).priority(0).reason(String.join("; ", reasons)).build());
            log.info("Created pending review task. documentId={}", document.getId());
        }
        log.info("AI invoice mapping completed. documentId={}, items={}, review={}", document.getId(), items.size(), review);
    }

    /** Maps invoice lines independently so absent or unconvertible values remain null for human correction. */
    private List<InvoiceItemEntity> mapItems(List<LineItem> lines, List<String> errors) {
        log.debug("Mapping AI invoice lines. count={}", lines.size());
        List<InvoiceItemEntity> items = new ArrayList<>();
        for (int index = 0; index < lines.size(); index++) {
            LineItem line = lines.get(index);
            String path = "line_items[" + index + "]";
            if (line == null) {
                errors.add(path + ": missing line");
                continue;
            }
            items.add(InvoiceItemEntity.builder()
                    .itemName(readField(line.getItemName(), path + ".item_name", errors))
                    .quantity(readQuantity(line.getQuantity(), path + ".quantity", errors))
                    .unitPrice(readMoney(line.getUnitPrice(), path + ".unit_price", errors))
                    .lineTotal(readMoney(line.getLineTotal(), path + ".line_total", errors)).build());
        }
        return items;
    }

    /** Returns found text, preserves absent values and records field-contract failures for review. */
    private String readField(FieldValue field, String path, List<String> errors) {
        log.debug("Mapping AI field. path={}", path);
        if (field == null) {
            errors.add(path + ": missing field");
            return null;
        }
        if ("absent".equals(field.getStatus()) && field.getValue() == null
                && field.getEvidence() != null && field.getEvidence().isEmpty()) {
            return null;
        }
        if ("found".equals(field.getStatus()) && field.getValue() != null
                && field.getEvidence() != null && !field.getEvidence().isEmpty()) {
            return field.getValue();
        }
        errors.add(path + ": inconsistent value, status or evidence");
        return null;
    }

    /** Converts a recognized amount exactly to the database money precision, leaving failed values for review. */
    private BigDecimal readMoney(FieldValue field, String path, List<String> errors) {
        log.debug("Converting AI amount. path={}", path);
        String value = readField(field, path, errors);
        if (value == null) {
            return null;
        }
        try {
            BigDecimal amount = new BigDecimal(value).setScale(2, RoundingMode.UNNECESSARY);
            if (amount.precision() > 18) {
                throw new ArithmeticException("Amount exceeds database precision");
            }
            return amount;
        } catch (NumberFormatException | ArithmeticException exception) {
            log.warn("AI amount cannot be mapped. path={}", path);
            errors.add(path + ": amount cannot be stored as decimal(18,2)");
            return null;
        }
    }

    /** Converts the recognized quantity to the existing finite Float storage type without inventing a missing value. */
    private Float readQuantity(FieldValue field, String path, List<String> errors) {
        log.debug("Converting AI quantity. path={}", path);
        String value = readField(field, path, errors);
        if (value == null) {
            return null;
        }
        try {
            BigDecimal quantity = new BigDecimal(value);
            float converted = quantity.floatValue();
            if (!Float.isFinite(converted) || (converted == 0 && quantity.signum() != 0)) {
                throw new NumberFormatException("Quantity exceeds Float range");
            }
            return converted;
        } catch (NumberFormatException exception) {
            log.warn("AI quantity cannot be mapped. path={}", path);
            errors.add(path + ": quantity cannot be stored as a finite Float");
            return null;
        }
    }

    /** Extracts the invoice calendar date from the AI local timestamp, preserving the original timestamp in raw JSON. */
    private LocalDate readDate(FieldValue field, List<String> errors) {
        log.debug("Converting AI invoice timestamp");
        String value = readField(field, "fields.timestamp", errors);
        if (value == null) {
            return null;
        }
        try {
            return LocalDateTime.parse(value).toLocalDate();
        } catch (DateTimeParseException exception) {
            log.warn("AI invoice timestamp cannot be mapped");
            errors.add("fields.timestamp: invalid local date-time");
            return null;
        }
    }
}
