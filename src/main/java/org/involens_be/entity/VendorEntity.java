package org.involens_be.entity;

import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

@Entity
@Table(name = "vendors")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VendorEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(name = "tax_code")
    private String taxCode;

    @Column(columnDefinition = "text")
    private String address;

    @Column(nullable = false)
    private String status;
}
