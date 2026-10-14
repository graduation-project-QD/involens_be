package org.involens_be.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.involens_be.common.UserRole;
import org.involens_be.config.OpenApiConfig;
import org.involens_be.dto.request.ExportPreviewRequest;
import org.involens_be.dto.response.BaseResponse;
import org.involens_be.dto.response.ExportPreviewResponse;
import org.involens_be.service.ExportJobService;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

@Slf4j (topic = "EXPORT-JOB-CONTROLLER")
@RestController
@RequestMapping("/api/v1/export-jobs")
@RequiredArgsConstructor
@Tag(name = "Export Jobs", description = "Invoice export preparation operations")
public class ExportJobController {

    private final ExportJobService exportJobService;

    /** Downloads an invoice-only Excel file within the caller's preview filters and visibility scope. */
    @Operation(summary = "Download invoice Excel workbook",
            security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH_SCHEME))
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Excel workbook downloaded"),
            @ApiResponse(responseCode = "400", description = "Invalid date range or status"),
            @ApiResponse(responseCode = "401", description = "JWT is missing or invalid"),
            @ApiResponse(responseCode = "404", description = "No visible invoices match the filters"),
            @ApiResponse(responseCode = "500", description = "Excel export failed")
    })
    @PostMapping("/excel")
    public ResponseEntity<byte[]> exportExcel(
            @ModelAttribute @ParameterObject ExportPreviewRequest request,
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt) {
        Long userId = readUserId(jwt);
        UserRole role = readRole(jwt);
        log.info("Received invoice Excel download request. userId={}, role={}", userId, role);
        byte[] workbook = exportJobService.exportExcel(request, userId, role);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"invoices.xlsx\"")
                .contentLength(workbook.length)
                .body(workbook);
    }

    /** Returns invoice counts that let the authenticated user review an export selection. */
    @Operation(summary = "Preview invoice export counts",
            security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH_SCHEME))
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Export preview returned"),
            @ApiResponse(responseCode = "400", description = "Invalid date range or status"),
            @ApiResponse(responseCode = "401", description = "JWT is missing or invalid")
    })
    @PostMapping("/preview")
    public BaseResponse<ExportPreviewResponse> preview(
            @ModelAttribute @ParameterObject ExportPreviewRequest request,
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt) {
        Long userId = readUserId(jwt);
        UserRole role = readRole(jwt);
        log.info("Received export preview request. userId={}, role={}", userId, role);
        var response = exportJobService.preview(request, userId, role);
        return BaseResponse.success(response);
    }

    /** Reads the authenticated database user ID embedded in an application-issued JWT. */
    private Long readUserId(Jwt jwt) {
        Object userIdClaim = jwt.getClaim("userId");
        if (userIdClaim instanceof Number number) {
            log.debug("Authenticated user ID read for export preview. userId={}", number.longValue());
            return number.longValue();
        }
        log.error("Authenticated token does not contain a numeric userId claim. subject={}", jwt.getSubject());
        throw new IllegalStateException("Authenticated token does not contain a valid userId");
    }

    /** Reads the application role embedded in an application-issued JWT. */
    private UserRole readRole(Jwt jwt) {
        String roleClaim = jwt.getClaimAsString("role");
        try {
            UserRole role = UserRole.valueOf(roleClaim);
            log.debug("Authenticated role read for export preview. role={}", role);
            return role;
        } catch (IllegalArgumentException | NullPointerException exception) {
            log.error("Authenticated token does not contain a valid role claim. subject={}", jwt.getSubject());
            throw new IllegalStateException("Authenticated token does not contain a valid role");
        }
    }
}
