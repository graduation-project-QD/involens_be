package org.involens_be.service;

import lombok.extern.slf4j.Slf4j;
import org.involens_be.common.DocumentState;
import org.involens_be.entity.VendorEntity;
import org.involens_be.repository.VendorRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Checks literal search handling and the uploader constraints passed to persistence. */
@Slf4j
class VendorServiceTest {
    /** Creates an active supplier with the trimmed seller name and supplied tax code for invoice linking. */
    @Test
    void createsSupplierForNewSeller() {
        log.info("Testing supplier creation for a new seller");
        var repository = mock(VendorRepository.class);
        when(repository.findByNormalizedName("ABC")).thenReturn(Optional.empty());
        when(repository.save(any(VendorEntity.class))).thenAnswer(invocation -> {
            VendorEntity vendor = invocation.getArgument(0);
            vendor.setId(7L);
            return vendor;
        });
        assertEquals(7L, new VendorService(repository).resolveVendorId("  ABC  ", "TAX-1"));
        verify(repository).save(argThat(vendor -> "ABC".equals(vendor.getName())
                && "ACTIVE".equals(vendor.getStatus()) && "TAX-1".equals(vendor.getTaxCode())));
    }

    /** Reuses the name match without overwriting supplier details from another invoice. */
    @Test
    void reusesSupplierWithoutUpdatingDetails() {
        log.info("Testing supplier reuse by seller name");
        var repository = mock(VendorRepository.class);
        var vendor = VendorEntity.builder().id(7L).name("ABC").taxCode("OLD-TAX").status("ACTIVE").build();
        when(repository.findByNormalizedName("abc")).thenReturn(Optional.of(vendor));
        assertEquals(7L, new VendorService(repository).resolveVendorId(" abc ", "NEW-TAX"));
        assertEquals("OLD-TAX", vendor.getTaxCode());
        verify(repository, never()).save(any());
    }

    /** Allows invoices with absent seller names to be saved without creating empty suppliers. */
    @Test
    void skipsSupplierForMissingSellerName() {
        log.info("Testing supplier resolution without seller names");
        var repository = mock(VendorRepository.class);
        var service = new VendorService(repository);
        for (String name : new String[]{null, "", "   "}) {
            assertNull(service.resolveVendorId(name, null));
        }
        verifyNoInteractions(repository);
    }

    /** Propagates supplier write failures so the surrounding invoice transaction cannot commit partially. */
    @Test
    void propagatesSupplierCreationFailure() {
        log.info("Testing supplier creation failure propagation");
        var repository = mock(VendorRepository.class);
        var failure = new IllegalStateException("Database unavailable");
        when(repository.save(any())).thenThrow(failure);
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> new VendorService(repository).resolveVendorId("ABC", null)));
    }

    /** Prevents wildcard input from broadening supplier searches and preserves uploader scope. */
    @Test
    void searchesLiteralNamesWithinEligibleUploads() {
        log.info("Testing scoped literal supplier search");
        var repository = mock(VendorRepository.class);
        var states = List.of(DocumentState.EXTRACT_SUCCESS, DocumentState.REVIEW_REQUIRED, DocumentState.EXTRACT_FAILED);
        when(repository.findForUploader(12L, "%abc!_50!%!!%", states)).thenReturn(List.of());
        assertTrue(new VendorService(repository).listVendors(12L, "  ABC_50%!  ").isEmpty());
        verify(repository).findForUploader(12L, "%abc!_50!%!!%", states);
    }

    /** Allows dropdown loading without a keyword, including blank search text. */
    @Test
    void ignoresAbsentAndBlankSearch() {
        log.info("Testing unfiltered supplier dropdown loading");
        var repository = mock(VendorRepository.class);
        var service = new VendorService(repository);
        for (String query : new String[]{null, "", "   "}) {
            assertTrue(service.listVendors(12L, query).isEmpty());
        }
        verify(repository, times(3)).findForUploader(eq(12L), isNull(), anyList());
    }

    /** Keeps database failures distinguishable from a successful empty supplier list. */
    @Test
    void propagatesPersistenceFailure() {
        log.info("Testing supplier persistence failure");
        var repository = mock(VendorRepository.class);
        var failure = new IllegalStateException("Database unavailable");
        when(repository.findForUploader(eq(12L), isNull(), anyList())).thenThrow(failure);
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> new VendorService(repository).listVendors(12L, null)));
    }
}
