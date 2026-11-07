package org.involens_be.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.involens_be.common.DocumentState;
import org.involens_be.dto.response.VendorListItemResponse;
import org.involens_be.repository.VendorRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;

/** Supplies invoice filter options scoped to the authenticated uploader. */
@Slf4j
@Service
@RequiredArgsConstructor
public class VendorService {
    private final VendorRepository vendors;

    /** Finds active suppliers of the caller's eligible uploads, optionally matching their names. */
    @Transactional(readOnly = true)
    public List<VendorListItemResponse> listVendors(Long userId, String query) {
        log.info("Listing invoice suppliers. userId={}", userId);
        try {
            var result = vendors.findForUploader(userId, keywordPattern(query),
                    List.of(DocumentState.EXTRACT_SUCCESS, DocumentState.REVIEW_REQUIRED, DocumentState.EXTRACT_FAILED));
            log.info("Invoice suppliers loaded. userId={}, returned={}", userId, result.size());
            return result;
        } catch (RuntimeException exception) {
            log.error("Failed to list invoice suppliers. userId={}", userId, exception);
            throw exception;
        }
    }

    /** Converts a supplier name search to a literal substring, treating blank text as no filter. */
    private String keywordPattern(String query) {
        log.debug("Preparing supplier name search. filtered={}", query != null && !query.isBlank());
        if (query == null || query.isBlank()) {
            return null;
        }
        return "%" + query.trim().toLowerCase(Locale.ROOT)
                .replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    }
}
