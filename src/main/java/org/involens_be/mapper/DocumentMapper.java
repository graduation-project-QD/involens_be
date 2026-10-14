package org.involens_be.mapper;

import org.involens_be.common.UploadStatus;
import org.involens_be.dto.response.DocumentDetailResponse;
import org.involens_be.dto.response.DocumentListItemResponse;
import org.involens_be.dto.response.DocumentUploaderResponse;
import org.involens_be.dto.response.DocumentUploadItemResponse;
import org.involens_be.dto.response.ExtractionResultResponse;
import org.involens_be.entity.DocumentEntity;
import org.involens_be.entity.ExtractionResultEntity;
import org.involens_be.entity.InvoiceEntity;
import org.involens_be.dto.response.InvoiceResponse;
import org.involens_be.dto.response.InvoiceItemResponse;
import org.involens_be.entity.InvoiceItemEntity;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.time.OffsetDateTime;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Mapper(componentModel = "spring")
public interface DocumentMapper {

    /** Maps document metadata and an uploader display name to a list item returned by the document API. */
    @Mapping(target = "uploadedByName", source = "uploaderName")
    @Mapping(target = "uploadedAt", expression = "java(toUtc(document.getUploadedAt()))")
    DocumentListItemResponse toListItemResponse(DocumentEntity document, String uploaderName);

    /** Maps document metadata and uploader information to the detailed document API response. */
    @Mapping(target = "uploadedBy", expression = "java(toUploaderResponse(document.getUploadedById(), uploaderName))")
    @Mapping(target = "uploadedAt", expression = "java(toUtc(document.getUploadedAt()))")
    @Mapping(target = "fileUrl", source = "fileUrl")
    DocumentDetailResponse toDetailResponse(DocumentEntity document, String uploaderName, String fileUrl);

    /** Maps persisted upload metadata to the per-file upload API response. */
    @Mapping(target = "uploadedAt", expression = "java(toSuccessfulUploadTime(document))")
    DocumentUploadItemResponse toUploadItemResponse(DocumentEntity document);

    /** Maps a persisted AI extraction outcome, including AI errors, to its public response. */
    @Mapping(target = "processingStartedAt", expression = "java(toUtc(extractionResult.getProcessingStartedAt()))")
    @Mapping(target = "processingFinishedAt", expression = "java(toUtc(extractionResult.getProcessingFinishedAt()))")
    @Mapping(target = "id", source = "extractionResult.id")
    @Mapping(target = "documentId", source = "extractionResult.documentId")
    @Mapping(target = "items", source = "items")
    @Mapping(target = "currency", source = "invoice.currency")
    @Mapping(target = "rawJson", source = "extractionResult.rawJson")
    @Mapping(target = "warnings", expression = "java(toWarnings(extractionResult.getRawJson()))")
    @Mapping(target = "polygons", expression = "java(toPolygons(extractionResult.getRawJson()))")
    ExtractionResultResponse toExtractionResultResponse(ExtractionResultEntity extractionResult,
            InvoiceEntity invoice, List<InvoiceItemResponse> items);

    /** Exposes original AI warnings without altering their codes, targets or additional metadata. */
    default List<?> toWarnings(Map<String, Object> rawJson) {
        org.slf4j.LoggerFactory.getLogger(DocumentMapper.class).debug("Mapping original AI review warnings");
        if (rawJson != null && rawJson.get("data") instanceof Map<?, ?> data
                && data.get("warnings") instanceof List<?> warnings) {
            return warnings;
        }
        return List.of();
    }

    /** Groups evidence by original header and zero-based line paths so the review screen can highlight AI regions. */
    default Map<String, List<Object>> toPolygons(Map<String, Object> rawJson) {
        org.slf4j.LoggerFactory.getLogger(DocumentMapper.class).debug("Mapping AI evidence polygons");
        Map<String, List<Object>> polygons = new LinkedHashMap<>();
        if (rawJson == null || !(rawJson.get("data") instanceof Map<?, ?> data)) {
            return polygons;
        }
        if (data.get("fields") instanceof Map<?, ?> fields) {
            appendFieldPolygons(fields, "fields.", polygons);
        }
        if (data.get("line_items") instanceof List<?> lines) {
            for (int index = 0; index < lines.size(); index++) {
                if (lines.get(index) instanceof Map<?, ?> line) {
                    appendFieldPolygons(line, "line_items[" + index + "].", polygons);
                }
            }
        }
        return polygons;
    }

    /** Copies all available evidence polygons for each field while retaining the AI coordinate values. */
    default void appendFieldPolygons(Map<?, ?> fields, String prefix, Map<String, List<Object>> polygons) {
        org.slf4j.LoggerFactory.getLogger(DocumentMapper.class).debug("Collecting AI field evidence. prefix={}", prefix);
        for (var entry : fields.entrySet()) {
            if (!(entry.getKey() instanceof String name) || !(entry.getValue() instanceof Map<?, ?> field)
                    || !(field.get("evidence") instanceof List<?> evidence)) {
                continue;
            }
            List<Object> regions = new ArrayList<>();
            for (Object region : evidence) {
                if (region instanceof Map<?, ?> details && details.get("polygon") instanceof List<?> polygon
                        && !polygon.isEmpty()) {
                    regions.add(polygon);
                }
            }
            if (!regions.isEmpty()) {
                polygons.put(prefix + name, regions);
            }
        }
    }

    /** Maps corrected invoice data and its approval timestamp to the public update response. */
    @Mapping(target = "approvedAt", expression = "java(toUtc(invoice.getApprovedAt()))")
    @Mapping(target = "items", source = "items")
    InvoiceResponse toInvoiceResponse(InvoiceEntity invoice, List<InvoiceItemResponse> items);

    /** Maps persisted invoice line amounts and quantities to the public response. */
    InvoiceItemResponse toInvoiceItemResponse(InvoiceItemEntity item);

    /** Builds the public uploader representation used in a document detail response. */
    default DocumentUploaderResponse toUploaderResponse(Long uploaderId, String uploaderName) {
        return DocumentUploaderResponse.builder().id(uploaderId).fullName(uploaderName).build();
    }

    /** Converts a persisted timestamp to UTC while preserving a missing timestamp. */
    default OffsetDateTime toUtc(OffsetDateTime timestamp) {
        return timestamp == null ? null : timestamp.withOffsetSameInstant(ZoneOffset.UTC);
    }

    /** Treats database extraction timestamps as UTC and exposes them with an explicit UTC offset. */
    default OffsetDateTime toUtc(LocalDateTime timestamp) {
        return timestamp == null ? null : timestamp.atOffset(ZoneOffset.UTC);
    }

    /** Returns the UTC upload time only for documents successfully stored in object storage. */
    default OffsetDateTime toSuccessfulUploadTime(DocumentEntity document) {
        return document.getUploadStatus() == UploadStatus.UPLOADED ? toUtc(document.getUploadedAt()) : null;
    }
}
