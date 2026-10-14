package org.involens_be.entity;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.util.UUID;

/** Stores one reviewed invoice line, linked to its invoice by identifier. */
@Entity
@Table(name = "invoice_item")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class InvoiceItemEntity {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(name = "invoice_id", nullable = false)
    private UUID invoiceId;
    @Column(name = "item_name")
    private String itemName;
    @Column
    private Float quantity;
    @Column(name = "unit_price", precision = 18, scale = 2)
    private BigDecimal unitPrice;
    @Column(name = "line_total", precision = 18, scale = 2)
    private BigDecimal lineTotal;
}
