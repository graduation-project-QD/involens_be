package org.involens_be.controller;

import org.involens_be.common.UserRole;
import org.involens_be.common.CommonStatus;
import org.involens_be.dto.response.AuthUserResponse;
import org.involens_be.exception.EmailAlreadyExistsException;
import org.involens_be.exception.GlobalExceptionHandler;
import org.involens_be.service.AuthService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AuthControllerTest {

    /** Returns the void success envelope for account soft deletion, including repeated requests. */
    @Test
    void managerSoftDeletesAccount() throws Exception {
        for (int attempt = 0; attempt < 2; attempt++) {
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/v1/auth/accounts/2"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.status").value(0))
                    .andExpect(jsonPath("$.data").doesNotExist());
        }
        verify(service, times(2)).deleteAccount(2L, UserRole.MANAGER);
    }

    /** Preserves the forbidden response for employees who attempt account deletion. */
    @Test
    void employeeCannotSoftDeleteAccount() throws Exception {
        setPrincipal(UserRole.EMPLOYEE);
        doThrow(new AccessDeniedException("Only managers can manage accounts"))
                .when(service).deleteAccount(2L, UserRole.EMPLOYEE);

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/v1/auth/accounts/2"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("ACCESS_DENIED"));
    }

    /** Returns the approved validation error when deletion would remove the last active manager. */
    @Test
    void lastManagerDeletionReturnsBadRequest() throws Exception {
        doThrow(new org.involens_be.exception.InvalidAccountOperationException(
                "Cannot deactivate or demote the last active manager"))
                .when(service).deleteAccount(2L, UserRole.MANAGER);

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/v1/auth/accounts/2"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    /** Returns the not-found response when the deletion target has never existed. */
    @Test
    void missingAccountDeletionReturnsNotFound() throws Exception {
        doThrow(new org.involens_be.exception.AccountNotFoundException())
                .when(service).deleteAccount(2L, UserRole.MANAGER);

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/v1/auth/accounts/2"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("ACCOUNT_NOT_FOUND"));
    }

    /** Returns HTTP 401 with an account-not-found message for login using a soft-deleted account. */
    @Test
    void inactiveAccountLoginReturnsUnauthorized() throws Exception {
        when(service.login(any())).thenThrow(new org.involens_be.exception.InvalidCredentialsException("Account not found"));

        mvc.perform(post("/api/v1/auth/login").contentType("application/json")
                        .content("{\"email\":\"user@example.com\",\"password\":\"Password!\"}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.status").value(1))
                .andExpect(jsonPath("$.error.code").value("AUTH_INVALID_CREDENTIALS"))
                .andExpect(jsonPath("$.error.message").value("Account not found"));
    }

    /** Returns not found when an inactive account is requested through the management detail API. */
    @Test
    void inactiveAccountDetailReturnsNotFound() throws Exception {
        when(service.getAccount(2L, UserRole.MANAGER)).thenThrow(new org.involens_be.exception.AccountNotFoundException());

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/auth/accounts/2"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("ACCOUNT_NOT_FOUND"));
    }

    /** Accepts ACTIVE and INACTIVE status changes while preserving the account response shape. */
    @Test
    void accountPatchAcceptsCommonStatuses() throws Exception {
        for (CommonStatus accountStatus : CommonStatus.values()) {
            when(service.updateAccount(eq(2L), any(), eq(UserRole.MANAGER))).thenReturn(
                    org.involens_be.dto.response.AccountListItemResponse.builder().id(2L).status(accountStatus).build());
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/v1/auth/accounts/2")
                            .contentType("application/json").content("{\"status\":\"" + accountStatus.name() + "\"}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value(accountStatus.name()));
        }
        var captor = org.mockito.ArgumentCaptor.forClass(org.involens_be.dto.request.UpdateAccountRequest.class);
        verify(service, times(2)).updateAccount(eq(2L), captor.capture(), eq(UserRole.MANAGER));
        org.junit.jupiter.api.Assertions.assertEquals(List.of(CommonStatus.ACTIVE, CommonStatus.INACTIVE),
                captor.getAllValues().stream().map(org.involens_be.dto.request.UpdateAccountRequest::getStatus).toList());
    }

    /** Returns management details using the existing response envelope. */
    @Test
    void managerGetsAccountDetail() throws Exception {
        when(service.getAccount(2L, UserRole.MANAGER)).thenReturn(
                org.involens_be.dto.response.AccountListItemResponse.builder().id(2L).status(CommonStatus.ACTIVE).build());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/auth/accounts/2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value(0))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));
    }

    /** Validates partial permission bodies and rejects null, invalid status, and invalid role fields. */
    @Test
    void accountPatchValidatesProvidedFields() throws Exception {
        for (String body : List.of("{\"role\":null}", "{\"status\":null}",
                "{\"status\":\"OTHER\"}", "{\"status\":\"LOCKED\"}", "{\"role\":\"OTHER\"}")) {
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/v1/auth/accounts/2")
                            .contentType("application/json").content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        }
        verifyNoInteractions(service);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/v1/auth/accounts/2")
                        .contentType("application/json").content("{\"role\":\"EMPLOYEE\"}"))
                .andExpect(status().isOk());
        var captor = org.mockito.ArgumentCaptor.forClass(org.involens_be.dto.request.UpdateAccountRequest.class);
        verify(service).updateAccount(eq(2L), captor.capture(), eq(UserRole.MANAGER));
        org.junit.jupiter.api.Assertions.assertEquals(UserRole.EMPLOYEE, captor.getValue().getRole());
        org.junit.jupiter.api.Assertions.assertNull(captor.getValue().getStatus());
    }

    /** Uses the authenticated user id for self-service password changes and validates inherited password rules. */
    @Test
    void selfPasswordChangeUsesAuthenticatedIdentity() throws Exception {
        mvc.perform(post("/api/v1/auth/me/change-password").contentType("application/json")
                        .content("{\"currentPassword\":\"OldPassword!\",\"newPassword\":\"NewPassword!\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value(0));
        verify(service).changePassword(eq(12L), any());
        clearInvocations(service);
        for (String password : List.of("short!", "lowercase!", "NoSpecial123", "Has Space!")) {
            mvc.perform(post("/api/v1/auth/me/change-password").contentType("application/json")
                            .content("{\"currentPassword\":\"OldPassword!\",\"newPassword\":\"" + password + "\"}"))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(service);
    }

    /** Routes manager password reset to the chosen account without requiring the existing password. */
    @Test
    void managerResetsPassword() throws Exception {
        mvc.perform(post("/api/v1/auth/accounts/2/reset-password").contentType("application/json")
                        .content("{\"newPassword\":\"NewPassword!\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value(0));
        verify(service).resetPassword(eq(2L), any(), eq(UserRole.MANAGER));
    }

    /** Verifies the default page contract and prevents credential exposure in the account response. */
    @Test
    void managerListsAccountsWithDefaultPagination() throws Exception {
        when(service.listAccounts(any(), eq(UserRole.MANAGER))).thenReturn(
                org.involens_be.dto.response.AccountListResponse.builder()
                        .items(List.of(org.involens_be.dto.response.AccountListItemResponse.builder()
                                .id(1L).email("manager@example.com").role(UserRole.MANAGER)
                                .status(CommonStatus.ACTIVE).build()))
                        .total(1).page(1).pageSize(8).build());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/auth/accounts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(0))
                .andExpect(jsonPath("$.data.page_size").value(8))
                .andExpect(jsonPath("$.data.items[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.items[0].passwordHash").doesNotExist());
        var captor = org.mockito.ArgumentCaptor.forClass(org.involens_be.dto.request.AccountListRequest.class);
        verify(service).listAccounts(captor.capture(), eq(UserRole.MANAGER));
        org.junit.jupiter.api.Assertions.assertEquals(1, captor.getValue().getPage());
        org.junit.jupiter.api.Assertions.assertEquals(8, captor.getValue().getPage_size());
    }

    /** Rejects invalid pagination before account data is requested from the service. */
    @Test
    void invalidAccountPaginationReturnsBadRequest() throws Exception {
        for (String query : List.of("page=0", "page_size=0", "page_size=101", "page=invalid")) {
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                            "/api/v1/auth/accounts?" + query))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        }
        verifyNoInteractions(service);
    }

    /** Preserves the forbidden response envelope for employees requesting account listings. */
    @Test
    void employeeAccountListingReturnsForbidden() throws Exception {
        setPrincipal(UserRole.EMPLOYEE);
        when(service.listAccounts(any(), eq(UserRole.EMPLOYEE)))
                .thenThrow(new AccessDeniedException("Only managers can list accounts"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/auth/accounts"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCESS_DENIED"));
    }

    private AuthService service;
    private MockMvc mvc;

    /** Configures isolated MVC serialization with a manager JWT principal. */
    @BeforeEach
    void setUp() {
        service = mock(AuthService.class);
        mvc = MockMvcBuilders.standaloneSetup(new AuthController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();
        setPrincipal(UserRole.MANAGER);
    }

    /** Clears the authenticated test principal after each account-creation contract test. */
    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
    }

    /** Accepts manager-selected EMPLOYEE details and returns the created public account. */
    @Test
    void managerCreatesEmployeeAccount() throws Exception {
        when(service.createAccount(any(), eq(UserRole.MANAGER))).thenReturn(AuthUserResponse.builder()
                .id(44L).email("employee@example.com").fullName("Employee Name").role(UserRole.EMPLOYEE).build());

        mvc.perform(post("/api/v1/auth/create-accounts")
                        .contentType("application/json")
                        .content("""
                                {"email":"employee@example.com","password":"Password!",
                                 "fullName":"Employee Name","role":"EMPLOYEE"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(0))
                .andExpect(jsonPath("$.data.id").value(44))
                .andExpect(jsonPath("$.data.role").value("EMPLOYEE"));
    }

    /** Rejects passwords that do not include the approved uppercase and special-character rules. */
    @Test
    void invalidPasswordReturnsBadRequest() throws Exception {
        mvc.perform(post("/api/v1/auth/create-accounts")
                        .contentType("application/json")
                        .content("""
                                {"email":"employee@example.com","password":"password",
                                 "fullName":"Employee Name","role":"EMPLOYEE"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(1))
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        verifyNoInteractions(service);
    }

    /** Returns the approved forbidden envelope when an employee invokes account creation. */
    @Test
    void employeeAccountCreationReturnsForbidden() throws Exception {
        setPrincipal(UserRole.EMPLOYEE);
        when(service.createAccount(any(), eq(UserRole.EMPLOYEE)))
                .thenThrow(new AccessDeniedException("Only managers can create accounts"));

        mvc.perform(post("/api/v1/auth/create-accounts")
                        .contentType("application/json")
                        .content("""
                                {"email":"employee@example.com","password":"Password!",
                                 "fullName":"Employee Name","role":"EMPLOYEE"}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCESS_DENIED"));
    }

    /** Returns HTTP 409 and the stable business code when the submitted email already exists. */
    @Test
    void duplicateEmailReturnsConflict() throws Exception {
        when(service.createAccount(any(), eq(UserRole.MANAGER))).thenThrow(new EmailAlreadyExistsException());

        mvc.perform(post("/api/v1/auth/create-accounts")
                        .contentType("application/json")
                        .content("""
                                {"email":"existing@example.com","password":"Password!",
                                 "fullName":"Existing User","role":"MANAGER"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("EMAIL_ALREADY_EXISTS"));
    }

    /** Replaces the authenticated JWT role used by the controller contract under test. */
    private void setPrincipal(UserRole role) {
        Jwt jwt = Jwt.withTokenValue("test").header("alg", "HS256").subject("manager@example.com")
                .claim("userId", 12L).claim("role", role.name()).build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(jwt, null, List.of()));
    }
}
