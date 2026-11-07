package org.involens_be.service;

import lombok.extern.slf4j.Slf4j;
import org.involens_be.common.DocumentState;
import org.involens_be.repository.VendorRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Checks literal search handling and the uploader constraints passed to persistence. */
@Slf4j
class VendorServiceTest {
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
