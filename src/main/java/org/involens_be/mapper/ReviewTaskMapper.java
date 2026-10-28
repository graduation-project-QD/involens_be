package org.involens_be.mapper;

import org.involens_be.dto.response.ReviewTaskListItemResponse;
import org.involens_be.repository.ReviewTaskRepository;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;
import com.google.gson.JsonParser;
import com.google.gson.JsonParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.stream.Collectors;

@Mapper(componentModel = "spring")
public interface ReviewTaskMapper {
    Logger LOG = LoggerFactory.getLogger(ReviewTaskMapper.class);

    /** Maps joined review-task data to the public list item and exposes its creation time as UTC. */
    @Mapping(target = "createdAt", expression = "java(toUtc(row.createdAt()))")
    @Mapping(target = "reason", source = "reason", qualifiedByName = "vietnameseReason")
    ReviewTaskListItemResponse toListItemResponse(ReviewTaskRepository.ReviewTaskRow row);

    /** Presents stored review concerns in Vietnamese without changing persisted AI diagnostics. */
    @Named("vietnameseReason")
    default String vietnameseReason(String reason) {
        LOG.debug("Translating review concerns for the queue response");
        if (reason == null || reason.isBlank()) {
            return reason;
        }
        String translated = Arrays.stream(reason.split("; ", -1))
                .map(this::translateConcern).collect(Collectors.joining("; "));
        LOG.debug("Review concern translation completed");
        return translated;
    }

    /** Translates known backend diagnostics and AI warnings, preserving unrecognized concerns verbatim. */
    private String translateConcern(String concern) {
        LOG.debug("Mapping a review concern to its Vietnamese description");
        if ("LOW_CONFIDENCE".equals(concern)) {
            return "Dữ liệu có độ tin cậy thấp.";
        }
        if (concern.startsWith("{")) {
            try {
                var warning = JsonParser.parseString(concern);
                if (warning.isJsonObject()) {
                    var object = warning.getAsJsonObject();
                    var code = object.get("code");
                    var target = object.get("target");
                    if (code != null && code.isJsonPrimitive() && "LOW_CONFIDENCE".equals(code.getAsString())) {
                        return target != null && target.isJsonPrimitive()
                                ? fieldLabel(target.getAsString()) + " có độ tin cậy thấp."
                                : "Dữ liệu có độ tin cậy thấp.";
                    }
                }
            } catch (JsonParseException exception) {
                LOG.debug("Preserving a review concern that is not valid warning JSON", exception);
            }
            LOG.debug("Preserving an unrecognized AI warning");
            return concern;
        }
        int separator = concern.indexOf(": ");
        if (separator < 0) {
            return concern;
        }
        String field = fieldLabel(concern.substring(0, separator));
        return switch (concern.substring(separator + 2)) {
            case "expected a number between 0 and 1" -> field + " phải là số trong khoảng từ 0 đến 1.";
            case "missing line" -> field + ": thiếu dữ liệu dòng hàng.";
            case "missing field" -> field + ": thiếu trường dữ liệu.";
            case "inconsistent value, status or evidence" -> field + ": giá trị, trạng thái hoặc bằng chứng không nhất quán.";
            case "amount cannot be stored as decimal(18,2)" -> field + ": số tiền không hợp lệ hoặc vượt giới hạn lưu trữ decimal(18,2).";
            case "quantity cannot be stored as a finite Float" -> field + ": số lượng không hợp lệ hoặc vượt giới hạn lưu trữ.";
            case "invalid local date-time" -> field + ": ngày giờ không hợp lệ.";
            default -> concern;
        };
    }

    /** Labels known invoice fields and line items while retaining unknown AI field paths for diagnosis. */
    private String fieldLabel(String path) {
        LOG.debug("Resolving the display label for a review field");
        if (path.matches("line_items\\[\\d+](\\.(item_name|quantity|unit_price|line_total))?")) {
            int bracket = path.indexOf(']');
            String line = "Dòng hàng " + new java.math.BigInteger(path.substring(11, bracket)).add(java.math.BigInteger.ONE);
            if (bracket == path.length() - 1) {
                return line;
            }
            String label = switch (path.substring(bracket + 2)) {
                case "item_name" -> "Tên hàng";
                case "quantity" -> "Số lượng";
                case "unit_price" -> "Đơn giá";
                case "line_total" -> "Thành tiền";
                default -> path;
            };
            return line + " - " + label;
        }
        return switch (path) {
            case "fields.seller" -> "Tên nhà cung cấp";
            case "fields.address" -> "Địa chỉ nhà cung cấp";
            case "fields.timestamp" -> "Ngày hóa đơn";
            case "fields.total_cost" -> "Tổng tiền";
            case "overall_confidence" -> "Độ tin cậy tổng thể";
            default -> path;
        };
    }

    /** Treats persisted review-task timestamps as UTC and adds an explicit UTC offset. */
    default OffsetDateTime toUtc(LocalDateTime timestamp) {
        return timestamp == null ? null : timestamp.atOffset(ZoneOffset.UTC);
    }
}
