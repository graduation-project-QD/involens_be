package org.involens_be.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.involens_be.common.UserRole;
import org.involens_be.config.OpenApiConfig;
import org.involens_be.dto.request.VendorItemReportRequest;
import org.involens_be.dto.response.BaseResponse;
import org.involens_be.dto.response.VendorItemReportResponse;
import org.involens_be.service.VendorItemReportService;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/reports/vendor-items")
public class VendorItemReportController {
    private final VendorItemReportService service;

    /** Returns supplier item totals for successful invoices in the authenticated caller's scope. */
    @GetMapping
    @Operation(summary = "Report invoice items by supplier",
            description = "Requires vendorId, dateFrom and dateTo (YYYY-MM-DD, inclusive invoice dates). "
                    + "Managers see all uploads; other roles see their own. Only ACTIVE, EXTRACT_SUCCESS documents. "
                    + "Items are grouped by trimmed case-insensitive names and sorted by amount descending, then name. "
                    + "Prices have two decimal places and percentage is in 0-100 units with one decimal place. "
                    + "Empty selections return zero totals and an empty list.",
            security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH_SCHEME))
    public BaseResponse<VendorItemReportResponse> report(
            @ModelAttribute @ParameterObject VendorItemReportRequest request,
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt) {
        log.info("Received supplier item report request. vendorId={}", request.getVendorId());
        var result = service.report(request, readUserId(jwt), readRole(jwt));
        log.info("Returning supplier item report. items={}", result.totalItems());
        return BaseResponse.success(result);
    }

    /** Downloads the supplier item report as Excel using the same required filters and permissions. */
    @GetMapping("/excel")
    @Operation(summary = "Download supplier item report Excel",
            description = "Uses the same filters and aggregates as the report endpoint. Empty selections produce a workbook with zero totals.",
            security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH_SCHEME))
    public ResponseEntity<byte[]> excel(
            @ModelAttribute @ParameterObject VendorItemReportRequest request,
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt) {
        log.info("Received supplier item Excel request. vendorId={}", request.getVendorId());
        byte[] bytes = service.exportExcel(request, readUserId(jwt), readRole(jwt));
        log.info("Returning supplier item Excel attachment. bytes={}", bytes.length);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"vendor-items.xlsx\"")
                .contentLength(bytes.length).body(bytes);
    }

    /** Reads the trusted uploader identifier used to restrict non-manager report access. */
    private Long readUserId(Jwt jwt) {
        log.debug("Reading supplier report caller identifier");
        Object value = jwt.getClaim("userId");
        if (value instanceof Number number) {
            return number.longValue();
        }
        log.error("Supplier report token has no numeric userId");
        throw new IllegalStateException("Authenticated token does not contain a valid userId");
    }

    /** Reads the trusted application role that determines whether all uploads are visible. */
    private UserRole readRole(Jwt jwt) {
        log.debug("Reading supplier report caller role");
        try {
            return UserRole.valueOf(jwt.getClaimAsString("role"));
        } catch (IllegalArgumentException | NullPointerException exception) {
            log.error("Supplier report token has no valid role", exception);
            throw new IllegalStateException("Authenticated token does not contain a valid role", exception);
        }
    }
}
