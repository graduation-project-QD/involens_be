package org.involens_be.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.involens_be.common.CommonStatus;
import org.involens_be.common.DocumentState;
import org.involens_be.common.UploadStatus;
import org.involens_be.common.UserRole;
import org.involens_be.entity.DocumentEntity;
import org.involens_be.repository.DocumentRepository;
import org.involens_be.repository.UserRepository;
import org.involens_be.service.AiExtractionResultService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import jakarta.persistence.EntityManager;
import java.time.LocalDate;

/** Seeds development invoices through the existing AI result persistence workflow. */
@Slf4j
@Component
@Profile("dev")
@ConditionalOnProperty(name = "app.mock-invoices.enabled", havingValue = "true")
@RequiredArgsConstructor
public class MockInvoiceDataInitializer {
    private final UserRepository users;
    private final DocumentRepository documents;
    private final AiExtractionResultService extractionResults;
    private final PlatformTransactionManager transactionManager;
    private final EntityManager entityManager;

    @Value("${app.admin.email:}")
    private String managerEmail;

    /** Seeds after account initialization; rolls back the batch on failure and preserves existing samples. */
    @EventListener(ApplicationReadyEvent.class)
    public void initialize() {
        log.info("Starting development mock invoice initialization");
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> seedInvoices());
            log.info("Development mock invoice initialization completed");
        } catch (RuntimeException exception) {
            log.error("Mock invoice initialization failed; sample batch rolled back", exception);
        }
    }

    /** Creates five pending reviews and five successful invoices owned by the configured active manager. */
    private void seedInvoices() {
        log.info("Looking up manager for mock invoices");
        var manager = users.findByEmailIgnoreCase(managerEmail.trim()).orElse(null);
        if (manager == null || manager.getRole() != UserRole.MANAGER
                || manager.getStatus() != CommonStatus.ACTIVE) {
            log.warn("Mock invoices skipped: configured account is not an active manager");
            return;
        }
        for (int index = 1; index <= 10; index++) {
            String filename = "mock-invoice-dev-" + index + ".pdf";
            Long existing = entityManager.createQuery(
                            "select count(d) from DocumentEntity d where d.originalFilename = :filename "
                                    + "and d.uploadedById = :owner", Long.class)
                    .setParameter("filename", filename).setParameter("owner", manager.getId()).getSingleResult();
            if (existing > 0) {
                log.info("Preserving existing mock invoice. filename={}", filename);
                continue;
            }
            var document = documents.saveAndFlush(DocumentEntity.builder()
                    .originalFilename(filename).mimeType("application/pdf").fileSize(0L)
                    .status(CommonStatus.ACTIVE).uploadStatus(UploadStatus.UPLOADED)
                    .currentState(DocumentState.PROCESSING).uploadedById(manager.getId()).build());
            extractionResults.saveResult(buildOutcome(document, index).toString());
            log.info("Mock invoice created. documentId={}, review={}", document.getId(), index <= 5);
        }
        log.info("Mock invoice batch prepared. managerId={}", manager.getId());
    }

    /** Builds synthetic VND invoice evidence, with missing addresses and low confidence for pending reviews. */
    private JsonObject buildOutcome(DocumentEntity document, int index) {
        log.debug("Building mock AI outcome. documentId={}", document.getId());
        boolean review = index <= 5;
        int unitPrice = 25000 * index;
        JsonObject fields = new JsonObject();
        fields.add("seller", field("Cua hang mau " + index));
        fields.add("address", field(review && index % 2 == 0 ? null : index + " Nguyen Trai, TP HCM"));
        fields.add("timestamp", field(LocalDate.of(2026, 10, index).atTime(9, 30).toString()));
        fields.add("total_cost", field(Integer.toString(unitPrice * 2)));
        JsonObject item = new JsonObject();
        item.addProperty("index", 1);
        item.add("item_name", field("Van phong pham mau " + index));
        item.add("quantity", field("2"));
        item.add("unit_price", field(Integer.toString(unitPrice)));
        item.add("line_total", field(Integer.toString(unitPrice * 2)));
        JsonArray items = new JsonArray();
        items.add(item);
        JsonArray warnings = new JsonArray();
        if (review) {
            JsonObject warning = new JsonObject();
            warning.addProperty("code", "LOW_CONFIDENCE");
            warning.addProperty("target", "fields.seller");
            warnings.add(warning);
        }
        JsonObject data = new JsonObject();
        data.addProperty("id", document.getId().toString());
        data.add("fields", fields);
        data.add("line_items", items);
        data.addProperty("currency", "VND");
        data.addProperty("overall_confidence", review ? 0.61 : 0.98);
        data.addProperty("needs_review", review);
        data.add("warnings", warnings);
        JsonObject outcome = new JsonObject();
        outcome.addProperty("status", 0);
        outcome.add("data", data);
        log.debug("Mock AI outcome built. documentId={}, review={}", document.getId(), review);
        return outcome;
    }

    /** Represents a synthetic recognized value or an absent field using the AI evidence contract. */
    private JsonObject field(String value) {
        log.trace("Building synthetic invoice field. absent={}", value == null);
        JsonObject field = new JsonObject();
        field.addProperty("value", value);
        field.addProperty("status", value == null ? "absent" : "found");
        JsonArray evidence = new JsonArray();
        if (value != null) {
            evidence.add(com.google.gson.JsonParser.parseString(
                    "{\"polygon\":[[10,10],[200,10],[200,40],[10,40]]}"));
        }
        field.add("evidence", evidence);
        return field;
    }
}
