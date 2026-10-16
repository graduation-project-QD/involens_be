package org.involens_be.service;

import io.minio.MinioClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.involens_be.common.CommonStatus;
import org.involens_be.common.DocumentState;
import org.involens_be.common.UploadStatus;
import org.involens_be.common.UserRole;
import org.involens_be.dto.response.DocumentSummaryResponse;
import java.time.LocalDate;
import java.util.EnumMap;
import org.involens_be.entity.DocumentEntity;
import org.involens_be.repository.DocumentRepository;
import org.involens_be.repository.ExtractionResultRepository;
import org.involens_be.repository.InvoiceRepository;
import org.involens_be.repository.ReviewTaskRepository;
import org.involens_be.common.AiStatus;
import org.involens_be.common.ReviewStatus;
import org.involens_be.dto.request.UpdateInvoiceExtractionRequest;
import org.involens_be.dto.response.UpdateExtractionResultResponse;
import org.involens_be.entity.ExtractionResultEntity;
import org.involens_be.entity.InvoiceEntity;
import org.involens_be.entity.InvoiceItemEntity;
import org.involens_be.repository.InvoiceItemRepository;
import org.involens_be.dto.response.InvoiceItemResponse;
import org.involens_be.exception.InvalidInvoiceUpdateException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import jakarta.persistence.criteria.Predicate;
import org.involens_be.dto.request.DocumentListRequest;
import org.involens_be.dto.response.DocumentListResponse;
import org.involens_be.dto.response.DocumentDetailResponse;
import org.involens_be.entity.UserEntity;
import org.involens_be.mapper.DocumentMapper;
import org.involens_be.exception.DocumentNotFoundException;
import org.involens_be.exception.ExtractionResultNotFoundException;
import org.involens_be.exception.InvalidDocumentFilterException;
import org.involens_be.repository.UserRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.math.BigDecimal;

@Slf4j(topic = "DOCUMENT-SERVICE")
@Service
@RequiredArgsConstructor
public class DocumentService {

    private final DocumentRepository documentRepository;
    private final DocumentStatusNotificationService statusNotifications;
    private final UserRepository userRepository;
    private final DocumentMapper documentMapper;
    private final ExtractionResultRepository extractionResultRepository;
    private final InvoiceRepository invoiceRepository;
    private final ReviewTaskRepository reviewTaskRepository;
    private final InvoiceItemRepository invoiceItemRepository;
    private static final ZoneId VIETNAM_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    @Value("${app.minio.bucket}")
    private String bucket;

    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.beans.factory.annotation.Qualifier("publicMinioClient")
    private MinioClient publicMinioClient;

    /** Deactivates an accessible active document while preserving its processing state and associated data. */
    @Transactional
    public void deactivateDocument(UUID documentId, Long userId, UserRole role) {
        log.info("Starting document deactivation. documentId={}", documentId);
        try {
            DocumentEntity document = findVisibleDocument(documentId, userId, role);
            log.debug("Document authorized for deactivation. documentId={}, state={}", documentId, document.getCurrentState());
            document.setStatus(CommonStatus.INACTIVE);
            documentRepository.save(document);
        } catch (DocumentNotFoundException exception) {
            log.warn("Document deactivation rejected: active document not visible. documentId={}, userId={}", documentId, userId);
            throw exception;
        } catch (RuntimeException exception) {
            log.error("Document deactivation failed. documentId={}, userId={}", documentId, userId, exception);
            throw exception;
        }
    }

    /** Returns all-time state totals and seven Vietnam calendar days of uploads within the user's scope. */
    @Transactional(readOnly = true)
    public DocumentSummaryResponse getSummary(Long ownerId, UserRole role) {
        log.info("Loading document dashboard summary. ownerId={}, role={}", ownerId, role);
        var counts = new EnumMap<DocumentState, Long>(DocumentState.class);
        for (Object[] row : documentRepository.countByState(CommonStatus.ACTIVE, role == UserRole.MANAGER, ownerId)) {
            counts.put((DocumentState) row[0], ((Number) row[1]).longValue());
        }
        var today = LocalDate.now(VIETNAM_ZONE);
        var dailyUploads = new ArrayList<DocumentSummaryResponse.DailyUploads>();
        for (int daysAgo = 6; daysAgo >= 0; daysAgo--) {
            var date = today.minusDays(daysAgo);
            var request = new DocumentListRequest();
            request.setDate_from(date);
            request.setDate_to(date);
            long count = documentRepository.count(filters(request, ownerId, role));
            dailyUploads.add(DocumentSummaryResponse.DailyUploads.builder()
                    .date(date).count(count).build());
        }
        var response = DocumentSummaryResponse.builder()
                .total(counts.values().stream().mapToLong(Long::longValue).sum())
                .processing(counts.getOrDefault(DocumentState.PROCESSING, 0L))
                .reviewRequired(counts.getOrDefault(DocumentState.REVIEW_REQUIRED, 0L))
                .success(counts.getOrDefault(DocumentState.EXTRACT_SUCCESS, 0L))
                .rejected(counts.getOrDefault(DocumentState.UPLOADED_FAILED, 0L))
                .failed(counts.getOrDefault(DocumentState.EXTRACT_FAILED, 0L))
                .dailyUploads(dailyUploads).build();
        return response;
    }

    /** Returns a filtered page of active documents belonging only to the authenticated uploader. */
    @Transactional(readOnly = true)
    public DocumentListResponse listDocuments(DocumentListRequest request, Long ownerId, UserRole role) {
        log.info("Listing active documents. ownerId={}, role={}, page={}, pageSize={}",
                ownerId, role, request.getPage(), request.getPage_size());
        validateFilters(request);
        var page = documentRepository.findAll(filters(request, ownerId, role),
                PageRequest.of(request.getPage() - 1, request.getPage_size(),
                        Sort.by(Sort.Direction.DESC, "uploadedAt", "id")));
        var items = page.getContent().stream()
                .map(document -> documentMapper.toListItemResponse(document,
                        userRepository.findById(document.getUploadedById())
                                .map(UserEntity::getFullName).orElse(null)))
                .toList();
        return DocumentListResponse.builder().items(items).total(page.getTotalElements())
                .page(request.getPage()).pageSize(request.getPage_size()).build();
    }

    /** Loads detail only when the requested document is active and belongs to the authenticated uploader. */
    @Transactional(readOnly = true)
    public DocumentDetailResponse getDocument(UUID documentId, Long ownerId, UserRole role) {
        log.info("Loading document detail. ownerId={}, role={}, documentId={}", ownerId, role, documentId);
        DocumentEntity document = findVisibleDocument(documentId, ownerId, role);
        String uploaderName = userRepository.findById(document.getUploadedById())
                .map(UserEntity::getFullName).orElse(null);
        var response = documentMapper.toDetailResponse(document, uploaderName, createFileUrl(document));
        return response;
    }

    /** Signs an hour-long browser GET URL for a stored image or PDF, preserving absent files as null. */
    private String createFileUrl(DocumentEntity document) {
        log.debug("Preparing document file URL. documentId={}", document.getId());
        if (document.getUploadStatus() != UploadStatus.UPLOADED
                || document.getObjectKey() == null || document.getObjectKey().isBlank()) {
            log.debug("Document has no successfully stored file. documentId={}", document.getId());
            return null;
        }
        try {
            String url = publicMinioClient.getPresignedObjectUrl(io.minio.GetPresignedObjectUrlArgs.builder()
                    .method(io.minio.http.Method.GET).bucket(bucket).object(document.getObjectKey())
                    .expiry(1, java.util.concurrent.TimeUnit.HOURS).build());
            log.debug("Document file URL created. documentId={}", document.getId());
            return url;
        } catch (Exception exception) {
            log.error("Document file URL creation failed. documentId={}", document.getId(), exception);
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR,
                    "Không thể tạo đường dẫn tài liệu. Vui lòng thử lại.", exception);
        }
    }

    /** Loads the AI extraction outcome only when its active document belongs to the authenticated uploader. */
    @Transactional(readOnly = true)
    public org.involens_be.dto.response.ExtractionResultResponse getExtractionResult(
            UUID documentId, Long ownerId, UserRole role) {
        log.info("Loading document extraction result. ownerId={}, role={}, documentId={}", ownerId, role, documentId);
        findVisibleDocument(documentId, ownerId, role);
        var extractionResult = extractionResultRepository.findByDocumentId(documentId)
                .orElseThrow(() -> {
                    log.warn("Extraction result not found. ownerId={}, documentId={}", ownerId, documentId);
                    return new ExtractionResultNotFoundException();
                });
        var invoice = invoiceRepository.findByExtractionResultId(extractionResult.getId()).orElse(null);
        var items = invoice == null ? null : loadInvoiceItems(invoice.getId());
        var response = documentMapper.toExtractionResultResponse(extractionResult, invoice, items);
        return response;
    }

    /**
     * Applies human corrections to extracted invoice data and completes the related review workflow atomically.
     * An empty object confirms an existing AI-produced invoice without changing its business fields.
     */
    @Transactional
    public UpdateExtractionResultResponse updateExtractionResult(
            UUID documentId, UpdateInvoiceExtractionRequest request, Long reviewerId, UserRole role) {
        log.info("Updating extracted invoice. documentId={}, reviewerId={}, role={}", documentId, reviewerId, role);
        if (request == null) {
            log.warn("Extracted invoice update rejected because the JSON body is null. documentId={}", documentId);
            throw new InvalidInvoiceUpdateException("Request body must be a JSON object");
        }

        DocumentEntity document = findVisibleDocument(documentId, reviewerId, role);
        ExtractionResultEntity extractionResult = extractionResultRepository.findByDocumentId(documentId)
                .orElseThrow(() -> {
                    log.warn("Extraction result not found for invoice update. documentId={}", documentId);
                    return new ExtractionResultNotFoundException();
                });
        InvoiceEntity invoice = invoiceRepository.findByExtractionResultId(extractionResult.getId())
                .orElseGet(() -> createInvoice(documentId, extractionResult.getId(), request));
        applyInvoiceUpdates(invoice, request);
        var replacementItems = request.hasField("items") ? validateInvoiceItems(request) : null;

        LocalDateTime completedAt = LocalDateTime.now(Clock.systemUTC());
        invoice.setApprovedById(reviewerId);
        invoice.setApprovedAt(completedAt);
        extractionResult.setAiStatus(AiStatus.SUCCESS);
        extractionResult.setHumanReviewRequired(false);
        DocumentState previousState = document.getCurrentState();
        document.setCurrentState(DocumentState.EXTRACT_SUCCESS);
        var reviewTasks = reviewTaskRepository.findAllByExtractionResultId(extractionResult.getId());
        reviewTasks.forEach(task -> {
            task.setStatus(ReviewStatus.COMPLETED);
            task.setCompletedAt(completedAt);
        });

        InvoiceEntity savedInvoice = invoiceRepository.save(invoice);
        if (replacementItems != null) {
            log.info("Replacing reviewed invoice items. invoiceId={}, count={}", savedInvoice.getId(), replacementItems.size());
            invoiceItemRepository.deleteAllByInvoiceId(savedInvoice.getId());
            replacementItems.forEach(item -> item.setInvoiceId(savedInvoice.getId()));
            invoiceItemRepository.saveAll(replacementItems);
        }
        extractionResultRepository.save(extractionResult);
        documentRepository.save(document);
        reviewTaskRepository.saveAll(reviewTasks);
        statusNotifications.afterCommit(document, previousState);
        var items = loadInvoiceItems(savedInvoice.getId());
        var response = UpdateExtractionResultResponse.builder()
                .extractionResult(documentMapper.toExtractionResultResponse(extractionResult, savedInvoice, items))
                .invoice(documentMapper.toInvoiceResponse(savedInvoice, items))
                .build();
        log.info("Extracted invoice updated successfully. documentId={}, invoiceId={}, completedTasks={}",
                documentId, savedInvoice.getId(), reviewTasks.size());
        return response;
    }

    /** Loads persisted invoice lines for GET and PATCH responses without imposing an order. */
    private List<InvoiceItemResponse> loadInvoiceItems(UUID invoiceId) {
        log.debug("Loading invoice items. invoiceId={}", invoiceId);
        var items = invoiceItemRepository.findAllByInvoiceId(invoiceId).stream()
                .map(documentMapper::toInvoiceItemResponse).toList();
        log.debug("Invoice items loaded. invoiceId={}, count={}", invoiceId, items.size());
        return items;
    }

    /** Validates a complete reviewed item list before any replacement is persisted. */
    private List<InvoiceItemEntity> validateInvoiceItems(UpdateInvoiceExtractionRequest request) {
        log.debug("Validating reviewed invoice items");
        if (request.getItems() == null) {
            log.warn("Invoice items replacement rejected because the list is null");
            throw new InvalidInvoiceUpdateException("items must be an array");
        }
        var items = new ArrayList<InvoiceItemEntity>();
        for (var item : request.getItems()) {
            if (item == null || item.getItemName() == null || item.getItemName().isBlank()
                    || item.getQuantity() == null || !Float.isFinite(item.getQuantity()) || item.getQuantity() <= 0
                    || item.getUnitPrice() == null || item.getUnitPrice().signum() < 0
                    || item.getLineTotal() == null || item.getLineTotal().signum() < 0) {
                log.warn("Invoice items replacement rejected. index={}", items.size());
                throw new InvalidInvoiceUpdateException("Each item requires non-blank item_name, finite quantity > 0, unit_price >= 0 and line_total >= 0");
            }
            items.add(InvoiceItemEntity.builder().itemName(item.getItemName()).quantity(item.getQuantity())
                    .unitPrice(item.getUnitPrice()).lineTotal(item.getLineTotal()).build());
        }
        log.debug("Reviewed invoice items validated. count={}", items.size());
        return items;
    }

    /** Creates an invoice shell after validating every database-required field in the PATCH request. */
    private InvoiceEntity createInvoice(
            UUID documentId, UUID extractionResultId, UpdateInvoiceExtractionRequest request) {
        log.info("Creating missing invoice during extraction review. documentId={}, extractionResultId={}",
                documentId, extractionResultId);
        InvoiceEntity invoice = InvoiceEntity.builder()
                .documentId(documentId)
                .extractionResultId(extractionResultId)
                .sellerName(readRequiredText(request.getSellerName(), "sellerName"))
                .invoiceNumber(readRequiredText(request.getInvoiceNumber(), "invoiceNumber"))
                .invoiceDate(readRequiredDate(request.getInvoiceDate(), "invoiceDate"))
                .totalAmount(readRequiredAmount(request.getTotalAmount(), "totalAmount"))
                .currency(readRequiredText(request.getCurrency(), "currency"))
                .sellerAddress(readOptionalText(request.getSellerAddress(), "sellerAddress"))
                .taxCode(readOptionalText(request.getTaxCode(), "taxCode"))
                .build();
        return invoice;
    }

    /** Applies only fields present in the PATCH document and permits null only for nullable invoice fields. */
    private void applyInvoiceUpdates(InvoiceEntity invoice, UpdateInvoiceExtractionRequest request) {
        log.debug("Applying supplied invoice fields. invoiceId={}", invoice.getId());
        if (request.hasField("sellerName")) {
            invoice.setSellerName(readRequiredText(request.getSellerName(), "sellerName"));
        }
        if (request.hasField("sellerAddress")) {
            invoice.setSellerAddress(readOptionalText(request.getSellerAddress(), "sellerAddress"));
        }
        if (request.hasField("invoiceNumber")) {
            invoice.setInvoiceNumber(readRequiredText(request.getInvoiceNumber(), "invoiceNumber"));
        }
        if (request.hasField("taxCode")) {
            invoice.setTaxCode(readOptionalText(request.getTaxCode(), "taxCode"));
        }
        if (request.hasField("invoiceDate")) {
            invoice.setInvoiceDate(readRequiredDate(request.getInvoiceDate(), "invoiceDate"));
        }
        if (request.hasField("totalAmount")) {
            invoice.setTotalAmount(readRequiredAmount(request.getTotalAmount(), "totalAmount"));
        }
        if (request.hasField("currency")) {
            invoice.setCurrency(readRequiredText(request.getCurrency(), "currency"));
        }
        log.debug("Supplied invoice fields applied. invoiceId={}", invoice.getId());
    }

    /** Reads a required non-blank textual invoice field. */
    private String readRequiredText(String value, String fieldName) {
        log.debug("Validating required invoice text field. field={}", fieldName);
        if (value == null || value.isBlank()) {
            log.warn("Required invoice text rejected. field={}", fieldName);
            throw new InvalidInvoiceUpdateException(fieldName + " must be a non-blank string");
        }
        return value;
    }

    /** Reads a nullable textual invoice field, preserving explicit JSON null as a cleared value. */
    private String readOptionalText(String value, String fieldName) {
        log.debug("Validating optional invoice text field. field={}", fieldName);
        return value;
    }

    /** Reads an ISO invoice date and rejects calendar dates later than today in Vietnam. */
    private LocalDate readRequiredDate(LocalDate value, String fieldName) {
        log.debug("Validating invoice date field. field={}", fieldName);
        if (value == null) {
            log.warn("Required invoice date is missing. field={}", fieldName);
            throw new InvalidInvoiceUpdateException(fieldName + " must use YYYY-MM-DD format");
        }
        if (value.isAfter(LocalDate.now(VIETNAM_ZONE))) {
            log.warn("Future invoice date rejected. field={}", fieldName);
            throw new InvalidInvoiceUpdateException(fieldName + " must not be in the future");
        }
        return value;
    }

    /** Reads a numeric invoice amount and enforces the approved strictly-positive business rule. */
    private BigDecimal readRequiredAmount(BigDecimal value, String fieldName) {
        log.debug("Validating invoice amount field. field={}", fieldName);
        if (value == null) {
            log.warn("Required invoice amount is missing. field={}", fieldName);
            throw new InvalidInvoiceUpdateException(fieldName + " must be a number greater than 0");
        }
        BigDecimal amount = value;
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("Non-positive invoice amount rejected. field={}", fieldName);
            throw new InvalidInvoiceUpdateException(fieldName + " must be greater than 0");
        }
        return amount;
    }

    /** Loads an active document globally for managers and owner-scoped for employees. */
    private DocumentEntity findVisibleDocument(UUID documentId, Long ownerId, UserRole role) {
        log.debug("Resolving visible document. ownerId={}, role={}, documentId={}", ownerId, role, documentId);
        var document = role == UserRole.MANAGER
                ? documentRepository.findByIdAndStatus(documentId, CommonStatus.ACTIVE)
                : documentRepository.findByIdAndUploadedByIdAndStatus(documentId, ownerId, CommonStatus.ACTIVE);
        return document.orElseThrow(DocumentNotFoundException::new);
    }

    /** Rejects invalid page bounds and reversed date ranges before querying document metadata. */
    private void validateFilters(DocumentListRequest request) {
        log.debug("Validating document list pagination and date range");
        if (request.getPage() < 1 || request.getPage_size() < 1 || request.getPage_size() > 100) {
            throw new InvalidDocumentFilterException("page must be >= 1 and page_size must be between 1 and 100");
        }
        if (request.getDate_from() != null && request.getDate_to() != null
                && request.getDate_from().isAfter(request.getDate_to())) {
            throw new InvalidDocumentFilterException("date_from must not be after date_to");
        }
    }

    /** Combines mandatory owner/active restrictions with optional AND filters and Vietnam calendar-day bounds. */
    private Specification<DocumentEntity> filters(DocumentListRequest request, Long ownerId, UserRole role) {
        log.debug("Building role-scoped document filters. ownerId={}, role={}", ownerId, role);
        OffsetDateTime from = request.getDate_from() == null ? null
                : request.getDate_from().atStartOfDay(VIETNAM_ZONE).toOffsetDateTime();
        OffsetDateTime until;
        try {
            until = request.getDate_to() == null ? null
                    : request.getDate_to().plusDays(1).atStartOfDay(VIETNAM_ZONE).toOffsetDateTime();
        } catch (DateTimeException exception) {
            throw new InvalidDocumentFilterException("date_to is outside the supported date range");
        }
        return (root, query, cb) -> {
            var predicates = new ArrayList<Predicate>();
            predicates.add(cb.equal(root.get("status"), CommonStatus.ACTIVE));
            if (role != UserRole.MANAGER) {
                predicates.add(cb.equal(root.get("uploadedById"), ownerId));
            } else if (request.getUploaded_by() != null) {
                predicates.add(cb.equal(root.get("uploadedById"), request.getUploaded_by()));
            }
            if (request.getCurrent_state() != null) {
                predicates.add(cb.equal(root.get("currentState"), request.getCurrent_state()));
            }
            for (String term : new String[]{request.getFilename(), request.getQ()}) {
                if (term != null && !term.isBlank()) {
                    predicates.add(cb.like(cb.lower(root.get("originalFilename")), containsPattern(term), '!'));
                }
            }
            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("uploadedAt"), from));
            }
            if (until != null) {
                predicates.add(cb.lessThan(root.get("uploadedAt"), until));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    /** Escapes SQL wildcard characters so filename searches match the submitted text literally. */
    private String containsPattern(String term) {
        log.debug("Preparing literal case-insensitive filename search");
        return "%" + term.trim().toLowerCase(Locale.ROOT)
                .replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    }

}
