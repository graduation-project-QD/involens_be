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
import org.involens_be.dto.request.LoginRequest;
import org.involens_be.dto.request.CreateUserAccountRequest;
import org.involens_be.dto.response.AuthUserResponse;
import org.involens_be.dto.response.BaseResponse;
import org.involens_be.dto.response.LoginResponse;
import org.involens_be.config.OpenApiConfig;
import org.involens_be.service.AuthService;
import org.involens_be.dto.request.AccountListRequest;
import org.involens_be.dto.request.UpdateAccountRequest;
import org.involens_be.dto.request.ChangePasswordRequest;
import org.involens_be.dto.request.ResetPasswordRequest;
import org.involens_be.dto.response.AccountListItemResponse;
import org.involens_be.dto.response.AccountListResponse;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.involens_be.common.UserRole;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Tag(name = "Authentication", description = "User authentication and current-session operations")
public class AuthController {

    private final AuthService authService;

    /** Returns active account management details to a manager, including status and audit timestamps. */
    @Operation(summary = "Get account details", security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH_SCHEME))
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Account returned"),
            @ApiResponse(responseCode = "401", description = "Invalid JWT"),
            @ApiResponse(responseCode = "403", description = "Manager required"),
            @ApiResponse(responseCode = "404", description = "Account not found or inactive")})
    @GetMapping("/accounts/{id}")
    public BaseResponse<AccountListItemResponse> getAccount(@PathVariable Long id,
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt) {
        var response = authService.getAccount(id, readRole(jwt));
        log.info("Account detail request completed. userId={}", id);
        return BaseResponse.success(response);
    }

    /** Applies manager-selected role/status changes and returns updated account information. */
    @Operation(summary = "Update account permissions", description = "Partial role/status update. "
            + "Status must be ACTIVE or INACTIVE; ACTIVE can restore a soft-deleted account. "
            + "Cannot remove the last active manager. Changed permissions revoke existing JWTs.",
            security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH_SCHEME))
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Account updated"),
            @ApiResponse(responseCode = "400", description = "Invalid change"),
            @ApiResponse(responseCode = "401", description = "Invalid JWT"),
            @ApiResponse(responseCode = "403", description = "Manager required"),
            @ApiResponse(responseCode = "404", description = "Account not found")})
    @PatchMapping("/accounts/{id}")
    public BaseResponse<AccountListItemResponse> updateAccount(@PathVariable Long id,
            @Valid @RequestBody UpdateAccountRequest request,
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt) {
        log.info("Received account permission update. userId={}", id);
        var response = authService.updateAccount(id, request, readRole(jwt));
        return BaseResponse.success(response);
    }

    /** Soft-deletes a manager-selected account and acknowledges success after revoking its existing sessions. */
    @Operation(summary = "Soft-delete an account", description = "Manager only. Sets status to INACTIVE and revokes JWTs. "
            + "Already inactive accounts succeed. Cannot delete the last active manager.",
            security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH_SCHEME))
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Account soft-deleted or already inactive"),
            @ApiResponse(responseCode = "400", description = "Cannot delete the last active manager"),
            @ApiResponse(responseCode = "401", description = "Invalid JWT"),
            @ApiResponse(responseCode = "403", description = "Manager required"),
            @ApiResponse(responseCode = "404", description = "Account not found")})
    @DeleteMapping("/accounts/{id}")
    public BaseResponse<Void> deleteAccount(@PathVariable Long id,
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt) {
        log.info("Received account soft deletion request. userId={}", id);
        authService.deleteAccount(id, readRole(jwt));
        return BaseResponse.success(null);
    }

    /** Changes the authenticated user's password and requires a new login after success. */
    @Operation(summary = "Change my password", security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH_SCHEME))
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Password changed; all sessions revoked"),
            @ApiResponse(responseCode = "400", description = "Invalid password or current password incorrect"),
            @ApiResponse(responseCode = "401", description = "Invalid JWT")})
    @PostMapping("/me/change-password")
    public BaseResponse<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request,
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt) {
        Long id = ((Number) jwt.getClaim("userId")).longValue();
        log.info("Received self-service password change. userId={}", id);
        authService.changePassword(id, request);
        return BaseResponse.success(null);
    }

    /** Resets a target account password for a manager and revokes that account's existing sessions. */
    @Operation(summary = "Reset account password", security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH_SCHEME))
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Password reset; all target sessions revoked"),
            @ApiResponse(responseCode = "400", description = "Invalid new password"),
            @ApiResponse(responseCode = "401", description = "Invalid JWT"),
            @ApiResponse(responseCode = "403", description = "Manager required"),
            @ApiResponse(responseCode = "404", description = "Account not found")})
    @PostMapping("/accounts/{id}/reset-password")
    public BaseResponse<Void> resetPassword(@PathVariable Long id,
            @Valid @RequestBody ResetPasswordRequest request,
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt) {
        log.info("Received manager password reset. userId={}", id);
        authService.resetPassword(id, request, readRole(jwt));
        return BaseResponse.success(null);
    }

    /** Returns a page of active accounts for an authenticated manager, excluding soft-deleted accounts. */
    @Operation(summary = "List accounts",
            description = "Manager only. Includes all roles with ACTIVE status, sorted by id ascending. "
                    + "Defaults: page=1, page_size=8; page_size must be between 1 and 100.",
            security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH_SCHEME))
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Account page returned, possibly empty"),
            @ApiResponse(responseCode = "400", description = "Invalid pagination"),
            @ApiResponse(responseCode = "401", description = "JWT is missing or invalid"),
            @ApiResponse(responseCode = "403", description = "Caller is not a manager")
    })
    @GetMapping("/accounts")
    public BaseResponse<AccountListResponse> listAccounts(
            @Valid @ModelAttribute @ParameterObject AccountListRequest request,
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt) {
        UserRole role = readRole(jwt);
        log.info("Received account listing request. role={}, page={}, pageSize={}",
                role, request.getPage(), request.getPage_size());
        var response = authService.listAccounts(request, role);
        return BaseResponse.success(response);
    }

    /**
     * Creates an active user account from credentials and a role supplied by an authenticated manager.
     *
     * @param request validated account details chosen by the manager
     * @param jwt authenticated token used to enforce manager-only account creation
     * @return successful response containing the newly created account
     */
    @Operation(
            summary = "Create an account",
            description = "Allows a manager to create an active EMPLOYEE or MANAGER account.",
            security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH_SCHEME))
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Account created successfully"),
            @ApiResponse(responseCode = "400", description = "Request validation failed"),
            @ApiResponse(responseCode = "401", description = "JWT is missing or invalid"),
            @ApiResponse(responseCode = "403", description = "Caller is not a manager"),
            @ApiResponse(responseCode = "409", description = "Email is already registered")
    })
    @PostMapping("/create-accounts")
    public BaseResponse<AuthUserResponse> createAccount(
            @Valid @RequestBody CreateUserAccountRequest request,
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt) {
        UserRole creatorRole = readRole(jwt);
        log.info("Received manager account creation request. email={}, requestedRole={}, creatorRole={}",
                request.getEmail(), request.getRole(), creatorRole);
        AuthUserResponse response = authService.createAccount(request, creatorRole);
        return BaseResponse.success(response);
    }

    /**
     * Authenticates an account using email and password and issues an access token.
     *
     * @param request validated login credentials
     * @return successful response containing token and user information
    */
    @Operation(
            summary = "Log in",
            description = "Authenticates an account and returns a JWT access token.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Authentication succeeded"),
            @ApiResponse(responseCode = "400", description = "Request validation failed"),
            @ApiResponse(responseCode = "401", description = "Email or password is invalid, or account does not exist")
    })
    @PostMapping("/login")
    public BaseResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        log.info("Received login request. email={}", request.getEmail());
        LoginResponse response = authService.login(request);
        return BaseResponse.success(response);
    }

    /**
     * Returns the public profile for the user represented by the bearer token.
     *
     * @param authentication current validated authentication
     * @return current user profile
    */
    @Operation(
            summary = "Get current user",
            description = "Returns the profile represented by the current JWT.",
            security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH_SCHEME))
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Current user returned successfully"),
            @ApiResponse(responseCode = "401", description = "JWT is missing or invalid, or account does not exist")
    })
    @GetMapping("/me")
    public BaseResponse<AuthUserResponse> me(@Parameter(hidden = true) Authentication authentication) {
        log.info("Received current-user request. subject={}", authentication.getName());
        AuthUserResponse response = authService.getCurrentUser(authentication);
        return BaseResponse.success(response);
    }

    /**
     * Completes stateless logout on the server; the client must discard its access token.
     *
     * @param authentication current validated authentication
     * @return success response acknowledging logout
    */
    @Operation(
            summary = "Log out",
            description = "Acknowledges stateless logout; the client must discard its JWT.",
            security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH_SCHEME))
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Logout acknowledged successfully"),
            @ApiResponse(responseCode = "401", description = "JWT is missing or invalid")
    })
    @PostMapping("/logout")
    public BaseResponse<Void> logout(@Parameter(hidden = true) Authentication authentication) {
        log.info("Processing stateless logout. subject={}", authentication.getName());
        return BaseResponse.ok(null, "Logout successfully");
    }

    /**
     * Reads the application role from a validated token for manager-only operations.
     *
     * @param jwt validated application-issued bearer token
     * @return role represented by the token
     */
    private UserRole readRole(Jwt jwt) {
        String roleClaim = jwt.getClaimAsString("role");
        try {
            UserRole role = UserRole.valueOf(roleClaim);
            log.debug("Authenticated role read for manager account operations. role={}", role);
            return role;
        } catch (IllegalArgumentException | NullPointerException exception) {
            log.error("Authenticated token does not contain a valid role claim. subject={}", jwt.getSubject());
            throw new IllegalStateException("Authenticated token does not contain a valid role");
        }
    }
}
