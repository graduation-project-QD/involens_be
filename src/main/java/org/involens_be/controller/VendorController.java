package org.involens_be.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.involens_be.config.OpenApiConfig;
import org.involens_be.dto.response.BaseResponse;
import org.involens_be.dto.response.VendorListItemResponse;
import org.involens_be.service.VendorService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Provides supplier options for the extracted-invoice filter. */
@Slf4j
@RestController
@RequestMapping("/api/v1/vendors")
@RequiredArgsConstructor
@Tag(name = "Vendors", description = "Invoice supplier filter options")
public class VendorController {
    private final VendorService vendorService;

    /** Returns suppliers linked to the authenticated caller's uploads, including for managers. */
    @GetMapping
    @Operation(summary = "List invoice suppliers",
            description = "Returns active suppliers linked to the caller's active documents in EXTRACT_SUCCESS, "
                    + "REVIEW_REQUIRED or EXTRACT_FAILED. Managers are also limited to their own uploads. "
                    + "Optional q matches supplier names using literal case-insensitive substrings; blank means no filter. "
                    + "No pagination. Each supplier appears once, ordered by name then ID. No matches returns an empty array.",
            security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH_SCHEME))
    public BaseResponse<List<VendorListItemResponse>> list(
            @RequestParam(name = "q", required = false) String query,
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt) {
        Long userId = readUserId(jwt);
        log.info("Received supplier list request. userId={}", userId);
        var result = vendorService.listVendors(userId, query);
        log.info("Supplier list request completed. userId={}", userId);
        return BaseResponse.success(result);
    }

    /** Reads the trusted uploader ID from the authenticated token to enforce supplier visibility. */
    private Long readUserId(Jwt jwt) {
        log.debug("Reading supplier caller identifier");
        Object userId = jwt.getClaim("userId");
        if (userId instanceof Number number) {
            return number.longValue();
        }
        log.error("Supplier caller token has no numeric userId claim");
        throw new IllegalStateException("Authenticated token does not contain a valid userId");
    }
}
