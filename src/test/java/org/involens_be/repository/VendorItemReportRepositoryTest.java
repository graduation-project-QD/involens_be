package org.involens_be.repository;

import lombok.extern.slf4j.Slf4j;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.involens_be.entity.DocumentEntity;
import org.involens_be.entity.InvoiceEntity;
import org.involens_be.entity.InvoiceItemEntity;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;
import java.time.LocalDate;

@Slf4j
class VendorItemReportRepositoryTest {
    /** Validates report joins, enum filters and projection types against Hibernate metadata without a live database. */
    @Test
    void validatesReportQuery() throws Exception {
        log.info("Validating supplier report HQL against entity metadata");
        var registry = new StandardServiceRegistryBuilder()
                .applySetting("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect")
                .applySetting("hibernate.boot.allow_jdbc_metadata_access", false)
                .applySetting("hibernate.hbm2ddl.auto", "none").build();
        try (var factory = new MetadataSources(registry)
                .addAnnotatedClass(InvoiceEntity.class).addAnnotatedClass(DocumentEntity.class)
                .addAnnotatedClass(InvoiceItemEntity.class).buildMetadata().buildSessionFactory();
             var session = factory.openSession()) {
            String hql = VendorItemReportRepository.class.getMethod("findReportRows", Long.class,
                    LocalDate.class, LocalDate.class, boolean.class, Long.class).getAnnotation(Query.class).value();
            session.createQuery(hql, VendorItemReportRepository.ReportRow.class)
                    .setParameter("vendorId", 3L).setParameter("dateFrom", LocalDate.parse("2026-01-01"))
                    .setParameter("dateTo", LocalDate.parse("2026-01-31"))
                    .setParameter("manager", false).setParameter("userId", 12L);
        } finally {
            StandardServiceRegistryBuilder.destroy(registry);
        }
    }
}
