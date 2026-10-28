package org.involens_be.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.involens_be.common.UserRole;
import org.involens_be.config.OpenApiConfig;
import org.involens_be.dto.request.ReviewTaskListRequest;
import org.involens_be.dto.response.BaseResponse;
import org.involens_be.dto.response.ReviewTaskListResponse;
import org.involens_be.service.ReviewTaskService;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/v1/review-tasks")
@RequiredArgsConstructor
@Tag(name = "Review Tasks", description = "Human invoice review operations")
public class ReviewTaskController {

    private final ReviewTaskService reviewTaskService;

    /** Lists review tasks visible to the authenticated employee or manager. */
    @Operation(summary = "List visible invoice review tasks",
            description = "Employees see tasks for their uploads; managers see all tasks. "
                    + "Optional q matches filename or uploader, ignoring case. "
                    + "date_from/date_to filter upload dates inclusively in Asia/Ho_Chi_Minh. "
                    + "Defaults: page=1 and page_size=20 (maximum 100).",
            security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH_SCHEME))
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Review-task page returned, possibly empty"),
            @ApiResponse(responseCode = "400", description = "Invalid status, upload-date range or pagination"),
            @ApiResponse(responseCode = "401", description = "JWT is missing or invalid")
    })
    @GetMapping
    public BaseResponse<ReviewTaskListResponse> list(
            @Valid @ModelAttribute @ParameterObject ReviewTaskListRequest request,
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt) {
        Long userId = readUserId(jwt);
        UserRole role = readRole(jwt);
        log.info("Received review-task list request. userId={}, role={}", userId, role);
        var response = reviewTaskService.listReviewTasks(request, userId, role);
        log.info("Review-task list request completed. userId={}, role={}", userId, role);
        return BaseResponse.success(response);
    }

    /** Reads the authenticated database user ID embedded in an application-issued JWT. */
    private Long readUserId(Jwt jwt) {
        Object userIdClaim = jwt.getClaim("userId");
        if (userIdClaim instanceof Number number) {
            return number.longValue();
        }
        log.error("Authenticated token does not contain a numeric userId claim. subject={}", jwt.getSubject());
        throw new IllegalStateException("Authenticated token does not contain a valid userId");
    }

    /** Reads the application role embedded in an application-issued JWT. */
    private UserRole readRole(Jwt jwt) {
        String roleClaim = jwt.getClaimAsString("role");
        try {
            return UserRole.valueOf(roleClaim);
        } catch (IllegalArgumentException | NullPointerException exception) {
            log.error("Authenticated token does not contain a valid role claim. subject={}", jwt.getSubject());
            throw new IllegalStateException("Authenticated token does not contain a valid role");
        }
    }
}
