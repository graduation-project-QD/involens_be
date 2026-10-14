package org.involens_be.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "invoices")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class InvoiceEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "document_id", nullable = false, unique = true)
    private UUID documentId;

    @Column(name = "extraction_result_id", nullable = false, unique = true)
    private UUID extractionResultId;

    @Column(name = "vendor_id")
    private UUID vendorId;

    @Column(name = "seller_name")
    private String sellerName;

    @Column(name = "seller_address", columnDefinition = "text")
    private String sellerAddress;

    @Column(name = "invoice_number")
    private String invoiceNumber;

    @Column(name = "tax_code")
    private String taxCode;

    @Column(name = "invoice_date")
    private LocalDate invoiceDate;

    @Column(name = "total_amount", precision = 18, scale = 2)
    private BigDecimal totalAmount;

    @Column(nullable = false)
    private String currency;

    @Column(name = "approved_by")
    private Long approvedById;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

}
