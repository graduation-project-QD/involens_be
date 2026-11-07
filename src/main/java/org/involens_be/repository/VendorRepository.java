package org.involens_be.repository;

import org.involens_be.common.DocumentState;
import org.involens_be.dto.response.VendorListItemResponse;
import org.involens_be.entity.VendorEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface VendorRepository extends JpaRepository<VendorEntity, Long> {
    /** Finds the supplier shared by invoices whose seller names differ only in case or surrounding spaces. */
    @Query("select v from VendorEntity v where lower(trim(v.name)) = lower(trim(:name))")
    Optional<VendorEntity> findByNormalizedName(@Param("name") String name);

    /** Lists each active supplier once when an eligible invoice belongs to the uploader. */
    @Query("""
            select new org.involens_be.dto.response.VendorListItemResponse(v.id, v.name)
            from VendorEntity v
            where v.status = 'ACTIVE'
              and (:keywordPattern is null or lower(v.name) like :keywordPattern escape '!')
              and exists (
                  select i.id from InvoiceEntity i
                  join DocumentEntity d on d.id = i.documentId
                  where i.vendorId = v.id
                    and d.uploadedById = :userId
                    and d.status = org.involens_be.common.CommonStatus.ACTIVE
                    and d.currentState in :states
              )
            order by v.name asc, v.id asc
            """)
    List<VendorListItemResponse> findForUploader(@Param("userId") Long userId,
            @Param("keywordPattern") String keywordPattern, @Param("states") List<DocumentState> states);
}
