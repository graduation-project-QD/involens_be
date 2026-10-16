package org.involens_be.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.involens_be.common.UserRole;
import org.involens_be.config.OpenApiConfig;
import org.involens_be.dto.request.InvoiceListRequest;
import org.involens_be.dto.response.BaseResponse;
import org.involens_be.dto.response.InvoiceListResponse;
import org.involens_be.service.InvoiceService;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Serves the dedicated extracted-invoice list without changing document APIs. */
@Slf4j
@RestController
@RequestMapping("/api/v1/invoices")
@RequiredArgsConstructor
@Tag(name = "Invoices", description = "Extracted invoice outcomes")
public class InvoiceController {
    private final InvoiceService invoiceService;

    /** Returns paginated success, pending-review and failed extraction outcomes visible to the caller. */
    @GetMapping
    @Operation(summary = "List extracted invoice outcomes",
            description = "Optional current_state: EXTRACT_SUCCESS, REVIEW_REQUIRED or EXTRACT_FAILED. "
                    + "Omit it for all three. Managers see all active documents; employees see their own. "
                    + "Defaults: page=1, page_size=8 (maximum 100). Newest uploads first, then document ID descending. "
                    + "Failed outcomes may have null invoice fields and ID; document_id is always present.",
            security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH_SCHEME))
    public BaseResponse<InvoiceListResponse> list(
            @Valid @ModelAttribute @ParameterObject InvoiceListRequest request,
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt) {
        Long userId = readUserId(jwt);
        UserRole role = readRole(jwt);
        log.info("Received extracted-invoice list request. userId={}, role={}", userId, role);
        var response = invoiceService.listInvoices(request, userId, role);
        log.info("Extracted-invoice list request completed. userId={}", userId);
        return BaseResponse.success(response);
    }

    /** Reads the authenticated uploader identifier used to limit employee invoice visibility. */
    private Long readUserId(Jwt jwt) {
        log.debug("Reading invoice caller identifier");
        Object userId = jwt.getClaim("userId");
        if (userId instanceof Number number) {
            return number.longValue();
        }
        log.error("Invoice caller token has no numeric userId claim");
        throw new IllegalStateException("Authenticated token does not contain a valid userId");
    }

    /** Reads the authenticated role that determines global or uploader-scoped invoice access. */
    private UserRole readRole(Jwt jwt) {
        log.debug("Reading invoice caller role");
        try {
            return UserRole.valueOf(jwt.getClaimAsString("role"));
        } catch (IllegalArgumentException | NullPointerException exception) {
            log.error("Invoice caller token has no valid role claim", exception);
            throw new IllegalStateException("Authenticated token does not contain a valid role", exception);
        }
    }
}
