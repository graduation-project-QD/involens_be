package org.involens_be.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSetter;
import lombok.Data;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;
import java.util.List;

@Data
@Slf4j
public class UpdateInvoiceExtractionRequest {
        private String sellerName;
    private String sellerAddress;
    private String invoiceNumber;
    private String taxCode;
    private LocalDate invoiceDate;
    private BigDecimal totalAmount;
    private String currency;
    private List<InvoiceItemRequest> items;

    /** Records a complete replacement list while distinguishing omission from explicit null. */
    @JsonSetter("items")
    public void setItems(List<InvoiceItemRequest> value) {
        log.debug("Recording reviewed invoice items");
        items = value;
        suppliedFields.add("items");
    }

    @JsonIgnore
    @Getter(lombok.AccessLevel.NONE)
    private final Set<String> suppliedFields = new HashSet<>();

    /** Identifies fields supplied by the reviewer, including explicit null values. */
    public boolean hasField(String fieldName) {
        log.debug("Checking supplied invoice field. field={}", fieldName);
        return suppliedFields.contains(fieldName);
    }

    /** Records the reviewed seller name and its presence for partial updates. */
    @JsonSetter("sellerName")
    public void setSellerName(String value) {
        log.debug("Recording reviewed seller name");
        sellerName = value;
        suppliedFields.add("sellerName");
    }

    /** Records the seller address, allowing explicit null to clear it. */
    @JsonSetter("sellerAddress")
    public void setSellerAddress(String value) {
        log.debug("Recording reviewed seller address");
        sellerAddress = value;
        suppliedFields.add("sellerAddress");
    }

    /** Records the reviewed invoice number and its presence for partial updates. */
    @JsonSetter("invoiceNumber")
    public void setInvoiceNumber(String value) {
        log.debug("Recording reviewed invoice number");
        invoiceNumber = value;
        suppliedFields.add("invoiceNumber");
    }

    /** Records the tax code, allowing explicit null to clear it. */
    @JsonSetter("taxCode")
    public void setTaxCode(String value) {
        log.debug("Recording reviewed tax code");
        taxCode = value;
        suppliedFields.add("taxCode");
    }

    /** Records the reviewed invoice date and its presence for validation. */
    @JsonSetter("invoiceDate")
    public void setInvoiceDate(LocalDate value) {
        log.debug("Recording reviewed invoice date");
        invoiceDate = value;
        suppliedFields.add("invoiceDate");
    }

    /** Records the reviewed decimal amount without losing precision. */
    @JsonSetter("totalAmount")
    public void setTotalAmount(BigDecimal value) {
        log.debug("Recording reviewed invoice amount");
        totalAmount = value;
        suppliedFields.add("totalAmount");
    }

    /** Records the reviewed currency and its presence for partial updates. */
    @JsonSetter("currency")
    public void setCurrency(String value) {
        log.debug("Recording reviewed invoice currency");
        currency = value;
        suppliedFields.add("currency");
    }
}
