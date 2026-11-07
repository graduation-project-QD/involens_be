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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.WebDataBinder;
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
    @PostMapping
    @Operation(summary = "List extracted invoice outcomes",
            description = "Optional current_state: EXTRACT_SUCCESS, REVIEW_REQUIRED or EXTRACT_FAILED. "
                    + "Omit it for all three. Managers see all active documents; employees see their own. "
                    + "q matches the filename or uploader full name using literal case-insensitive substrings. "
                    + "Send q and vendor_ids in a JSON body; vendor_ids is an array matching any selected supplier ID. "
                    + "Send {} for no text or supplier filter. Other filters and pagination remain query parameters. "
                    + "Other filters are combined with AND. "
                    + "Defaults: page=1, page_size=8 (maximum 100). Newest uploads first, then document ID descending. "
                    + "Failed outcomes may have null invoice fields and ID; document_id is always present.",
            security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH_SCHEME))
    public BaseResponse<InvoiceListResponse> list(
            @Valid @ModelAttribute("filters") @ParameterObject InvoiceListRequest request,
            @RequestBody InvoiceListRequest.Filters body,
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt) {
        Long userId = readUserId(jwt);
        UserRole role = readRole(jwt);
        log.info("Received extracted-invoice list request. userId={}, role={}", userId, role);
        request.setQ(body.q());
        request.setVendor_ids(body.vendor_ids());
        var response = invoiceService.listInvoices(request, userId, role);
        log.info("Extracted-invoice list request completed. userId={}", userId);
        return BaseResponse.success(response);
    }

    /** Binds date, state and pagination from the URL while text and supplier selections come from JSON. */
    @InitBinder("filters")
    public void bindQueryFilters(WebDataBinder binder) {
        log.debug("Binding invoice date, state and pagination query filters");
        binder.setAllowedFields("date_from", "date_to", "current_state", "page", "page_size");
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
