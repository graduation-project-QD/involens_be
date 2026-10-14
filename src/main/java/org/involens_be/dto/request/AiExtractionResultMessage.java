package org.involens_be.dto.request;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Carries the AI extraction outcome and its original evidence for a submitted document. */
@Getter
public class AiExtractionResultMessage {
    @Expose
    private Integer status;
    @Expose
    private ExtractionError error;
    @Expose
    private ExtractionData data;

    /** Describes why the AI worker could not extract the submitted document. */
    @Getter
    public static class ExtractionError {
        @Expose
        private String code;
        @Expose
        private String message;
    }

    /** Links the outcome to the submitted document; failed outcomes may contain only its ID. */
    @Getter
    public static class ExtractionData {
        @Expose
        private UUID id;
        @Expose
        private InvoiceFields fields;
        @Expose
        @SerializedName("line_items")
        private List<LineItem> lineItems;
        @Expose
        private String currency;
        @Expose
        @SerializedName("overall_confidence")
        private BigDecimal overallConfidence;
        @Expose
        @SerializedName("needs_review")
        private Boolean needsReview;
        @Expose
        private List<Warning> warnings;
        @Expose
        private Image image;
        @Expose
        private Meta meta;
    }

    /** Groups the invoice header values recognized by the AI worker. */
    @Getter
    public static class InvoiceFields {
        @Expose
        private FieldValue seller;
        @Expose
        private FieldValue address;
        @Expose
        private FieldValue timestamp;
        @Expose
        @SerializedName("total_cost")
        private FieldValue totalCost;
    }

    /** Preserves a recognized string or an absent value and its supporting image regions. */
    @Getter
    public static class FieldValue {
        @Expose
        private String value;
        /** AI field status: found or absent. */
        @Expose
        private String status;
        @Expose
        private List<Evidence> evidence;
    }

    /** Identifies the image region supporting a recognized value using integer coordinate pairs. */
    @Getter
    public static class Evidence {
        @Expose
        private List<List<Integer>> polygon;
    }

    /** Carries an invoice line with the index and values supplied by AI. */
    @Getter
    public static class LineItem {
        @Expose
        private Integer index;
        @Expose
        @SerializedName("item_name")
        private FieldValue itemName;
        @Expose
        private FieldValue quantity;
        @Expose
        @SerializedName("unit_price")
        private FieldValue unitPrice;
        @Expose
        @SerializedName("line_total")
        private FieldValue lineTotal;
    }

    /** Identifies an AI review concern and the field path reported by the worker. */
    @Getter
    public static class Warning {
        @Expose
        private String code;
        @Expose
        private String target;
    }

    /** Describes the image dimensions and rotation reported by AI for evidence interpretation. */
    @Getter
    public static class Image {
        @Expose
        private Integer width;
        @Expose
        private Integer height;
        @Expose
        @SerializedName("rotation_applied")
        private Integer rotationApplied;
    }

    /** Preserves the schema, model, OCR versions and processing duration reported by AI. */
    @Getter
    public static class Meta {
        @Expose
        @SerializedName("schema_version")
        private String schemaVersion;
        @Expose
        @SerializedName("model_version")
        private String modelVersion;
        @Expose
        @SerializedName("ocr_version")
        private String ocrVersion;
        @Expose
        @SerializedName("processing_ms")
        private Long processingMs;
    }
}
