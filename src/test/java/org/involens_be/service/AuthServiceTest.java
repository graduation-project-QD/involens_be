package org.involens_be.service;

import org.involens_be.common.UserRole;
import org.involens_be.common.CommonStatus;
import org.involens_be.dto.request.CreateUserAccountRequest;
import org.involens_be.entity.UserEntity;
import org.involens_be.exception.EmailAlreadyExistsException;
import org.involens_be.mapper.UserMapper;
import org.involens_be.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    /** Delegates refresh rotation and current-session logout to the transactional credential service. */
    @Test
    void delegatesRefreshAndLogout() {
        org.slf4j.LoggerFactory.getLogger(getClass()).info("Testing authentication refresh and logout delegation");
        var response = org.involens_be.dto.response.LoginResponse.builder().accessToken("new-access").build();
        when(refreshTokenService.refresh("refresh")).thenReturn(response);
        assertSame(response, service.refreshToken("refresh"));
        service.logout("refresh", 7L);
        verify(refreshTokenService).revoke("refresh", 7L);
    }

    /** Revokes a signed JWT after soft deletion and keeps it revoked when PATCH restores the account. */
    @Test
    void softDeletionRevokesSessionsEvenAfterRestoration() {
        var config = new org.involens_be.config.SecurityConfig();
        String secret = "account-token-test-secret-32-characters";
        var issuer = "account-test";
        var tokens = new JwtService(config.jwtEncoder(secret));
        org.springframework.test.util.ReflectionTestUtils.setField(tokens, "issuer", issuer);
        org.springframework.test.util.ReflectionTestUtils.setField(tokens, "expirationSeconds", 3600L);
        var user = UserEntity.builder().id(2L).email("user@example.com").role(UserRole.EMPLOYEE)
                .status(CommonStatus.ACTIVE).passwordHash("password-hash").tokenVersion(3).build();
        when(users.findById(2L)).thenReturn(java.util.Optional.of(user));
        when(users.findAllForAccountUpdate()).thenReturn(java.util.List.of(user));
        var decoder = config.jwtDecoder(secret, issuer, new org.involens_be.config.AccountTokenValidator(users));
        String oldToken = tokens.generateAccessToken(user);
        assertDoesNotThrow(() -> decoder.decode(oldToken));

        service.deleteAccount(2L, UserRole.MANAGER);

        assertEquals(CommonStatus.INACTIVE, user.getStatus());
        assertEquals(4, user.getTokenVersion());
        assertEquals("password-hash", user.getPasswordHash());
        assertThrows(org.springframework.security.oauth2.jwt.JwtValidationException.class, () -> decoder.decode(oldToken));
        service.deleteAccount(2L, UserRole.MANAGER);
        assertEquals(4, user.getTokenVersion());
        verify(users).saveAndFlush(user);
        verify(users, never()).delete(any(UserEntity.class));
        verify(users, never()).deleteById(anyLong());

        var request = new org.involens_be.dto.request.UpdateAccountRequest();
        org.springframework.test.util.ReflectionTestUtils.setField(request, "status", CommonStatus.ACTIVE);
        service.updateAccount(2L, request, UserRole.MANAGER);
        assertEquals(CommonStatus.ACTIVE, user.getStatus());
        assertEquals(5, user.getTokenVersion());
        assertThrows(org.springframework.security.oauth2.jwt.JwtValidationException.class, () -> decoder.decode(oldToken));
        assertDoesNotThrow(() -> decoder.decode(tokens.generateAccessToken(user)));
    }

    /** Preserves the final active manager even when inactive managers exist. */
    @Test
    void lastActiveManagerCannotBeSoftDeleted() {
        var manager = UserEntity.builder().id(1L).role(UserRole.MANAGER).status(CommonStatus.ACTIVE).build();
        var inactiveManager = UserEntity.builder().id(2L).role(UserRole.MANAGER).status(CommonStatus.INACTIVE).build();
        when(users.findAllForAccountUpdate()).thenReturn(java.util.List.of(manager, inactiveManager));

        assertThrows(org.involens_be.exception.InvalidAccountOperationException.class,
                () -> service.deleteAccount(1L, UserRole.MANAGER));

        assertEquals(CommonStatus.ACTIVE, manager.getStatus());
        assertEquals(0, manager.getTokenVersion());
        verify(users, never()).saveAndFlush(any());
    }

    /** Allows deleting one active manager while another active manager remains. */
    @Test
    void managerCanBeSoftDeletedWhenAnotherActiveManagerRemains() {
        var manager = UserEntity.builder().id(1L).role(UserRole.MANAGER).status(CommonStatus.ACTIVE).build();
        var other = UserEntity.builder().id(2L).role(UserRole.MANAGER).status(CommonStatus.ACTIVE).build();
        when(users.findAllForAccountUpdate()).thenReturn(java.util.List.of(manager, other));

        service.deleteAccount(1L, UserRole.MANAGER);

        assertEquals(CommonStatus.INACTIVE, manager.getStatus());
        assertEquals(1, manager.getTokenVersion());
        assertEquals(CommonStatus.ACTIVE, other.getStatus());
        verify(users).saveAndFlush(manager);
    }

    /** Rejects employee deletion before querying accounts and returns not found for a missing target. */
    @Test
    void softDeletionRequiresManagerAndExistingAccount() {
        assertThrows(AccessDeniedException.class, () -> service.deleteAccount(2L, UserRole.EMPLOYEE));
        verifyNoInteractions(users);
        when(users.findAllForAccountUpdate()).thenReturn(java.util.List.of());

        assertThrows(org.involens_be.exception.AccountNotFoundException.class,
                () -> service.deleteAccount(2L, UserRole.MANAGER));
        verify(users, never()).saveAndFlush(any());
    }

    /** Treats inactive accounts as nonexistent at login without checking passwords or issuing tokens. */
    @Test
    void inactiveAccountCannotLogIn() {
        var user = UserEntity.builder().id(2L).email("user@example.com").status(CommonStatus.INACTIVE).build();
        when(users.findByEmailIgnoreCase("user@example.com")).thenReturn(java.util.Optional.of(user));
        var request = new org.involens_be.dto.request.LoginRequest("user@example.com", "Password!");

        var exception = assertThrows(org.involens_be.exception.InvalidCredentialsException.class,
                () -> service.login(request));

        assertEquals("Account not found", exception.getMessage());
        verifyNoInteractions(passwordEncoder, refreshTokenService, userMapper);
    }

    /** Returns the public profile and access token when an active account supplies valid credentials. */
    @Test
    void activeAccountCanLogIn() {
        var user = UserEntity.builder().id(2L).email("user@example.com").passwordHash("hash")
                .status(CommonStatus.ACTIVE).build();
        when(users.findByEmailIgnoreCase("user@example.com")).thenReturn(java.util.Optional.of(user));
        when(passwordEncoder.matches("Password!", "hash")).thenReturn(true);
        when(refreshTokenService.issue(user)).thenReturn(org.involens_be.dto.response.LoginResponse.builder()
                .accessToken("signed-token").refreshToken("refresh-token").refreshExpiresIn(604800).build());

        var response = service.login(new org.involens_be.dto.request.LoginRequest("user@example.com", "Password!"));

        assertEquals("signed-token", response.getAccessToken());
        assertEquals("refresh-token", response.getRefreshToken());
        assertEquals(604800, response.getRefreshExpiresIn());
        verify(refreshTokenService).issue(user);
    }

    /** Hides a soft-deleted current user's profile even if authentication was resolved before deletion. */
    @Test
    void inactiveAccountCannotLoadCurrentProfile() {
        var user = UserEntity.builder().id(2L).status(CommonStatus.INACTIVE).build();
        when(users.findByEmailIgnoreCase("user@example.com")).thenReturn(java.util.Optional.of(user));
        var authentication = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                "user@example.com", null);

        var exception = assertThrows(org.involens_be.exception.InvalidCredentialsException.class,
                () -> service.getCurrentUser(authentication));

        assertEquals("Account not found", exception.getMessage());
        verifyNoInteractions(userMapper);
    }

    /** Exercises real JWT signing and decoding so revocation is enforced by the configured decoder. */
    @Test
    void signedTokenIsRejectedAfterSuccessfulPasswordChange() {
        var config = new org.involens_be.config.SecurityConfig();
        String secret = "account-token-test-secret-32-characters";
        var issuer = "account-test";
        var tokens = new JwtService(config.jwtEncoder(secret));
        org.springframework.test.util.ReflectionTestUtils.setField(tokens, "issuer", issuer);
        org.springframework.test.util.ReflectionTestUtils.setField(tokens, "expirationSeconds", 3600L);
        var user = UserEntity.builder().id(2L).email("user@example.com").role(UserRole.EMPLOYEE)
                .status(CommonStatus.ACTIVE).passwordHash("old-hash").build();
        when(users.findById(2L)).thenReturn(java.util.Optional.of(user));
        when(users.findByIdForUpdate(2L)).thenReturn(java.util.Optional.of(user));
        var decoder = config.jwtDecoder(secret, issuer, new org.involens_be.config.AccountTokenValidator(users));
        String oldToken = tokens.generateAccessToken(user);
        assertEquals(2L, ((Number) decoder.decode(oldToken).getClaim("userId")).longValue());
        var request = new org.involens_be.dto.request.ChangePasswordRequest();
        org.springframework.test.util.ReflectionTestUtils.setField(request, "currentPassword", "OldPassword!");
        org.springframework.test.util.ReflectionTestUtils.setField(request, "newPassword", "NewPassword!");
        when(passwordEncoder.matches("OldPassword!", "old-hash")).thenReturn(true);
        when(passwordEncoder.encode("NewPassword!")).thenReturn("new-hash");
        service.changePassword(2L, request);
        assertThrows(org.springframework.security.oauth2.jwt.JwtValidationException.class, () -> decoder.decode(oldToken));
        assertEquals(1L, ((Number) decoder.decode(tokens.generateAccessToken(user)).getClaim("tokenVersion")).longValue());
    }

    /** Rejects unauthorized account mutations and empty changes before obtaining database locks. */
    @Test
    void invalidAccountMutationsDoNotAccessDatabase() {
        var request = new org.involens_be.dto.request.UpdateAccountRequest();
        assertThrows(AccessDeniedException.class, () -> service.updateAccount(1L, request, UserRole.EMPLOYEE));
        assertThrows(AccessDeniedException.class, () -> service.resetPassword(1L,
                new org.involens_be.dto.request.ResetPasswordRequest("NewPassword!"), UserRole.EMPLOYEE));
        assertThrows(org.involens_be.exception.InvalidAccountOperationException.class,
                () -> service.updateAccount(1L, request, UserRole.MANAGER));
        verifyNoInteractions(users, passwordEncoder, userMapper);
    }

    /** Ensures missing accounts return the approved not-found business error. */
    @Test
    void accountDetailRequiresManagerAndExistingAccount() {
        assertThrows(AccessDeniedException.class, () -> service.getAccount(1L, UserRole.EMPLOYEE));
        verifyNoInteractions(users);
        when(users.findByIdAndStatus(1L, CommonStatus.ACTIVE)).thenReturn(java.util.Optional.empty());
        assertThrows(org.involens_be.exception.AccountNotFoundException.class,
                () -> service.getAccount(1L, UserRole.MANAGER));
    }

    /** Prevents removing the final active manager without changing its credentials or token version. */
    @Test
    void lastActiveManagerCannotBeDeactivatedOrDemoted() {
        var manager = UserEntity.builder().id(1L).role(UserRole.MANAGER).status(CommonStatus.ACTIVE).build();
        when(users.findAllForAccountUpdate()).thenReturn(java.util.List.of(manager));
        var request = new org.involens_be.dto.request.UpdateAccountRequest();
        org.springframework.test.util.ReflectionTestUtils.setField(request, "status", CommonStatus.INACTIVE);
        assertThrows(org.involens_be.exception.InvalidAccountOperationException.class,
                () -> service.updateAccount(1L, request, UserRole.MANAGER));
        org.springframework.test.util.ReflectionTestUtils.setField(request, "status", null);
        org.springframework.test.util.ReflectionTestUtils.setField(request, "role", UserRole.EMPLOYEE);
        assertThrows(org.involens_be.exception.InvalidAccountOperationException.class,
                () -> service.updateAccount(1L, request, UserRole.MANAGER));
        assertEquals(UserRole.MANAGER, manager.getRole());
        assertEquals(CommonStatus.ACTIVE, manager.getStatus());
        assertEquals(0, manager.getTokenVersion());
        verify(users, never()).saveAndFlush(any());
    }

    /** Allows self-demotion when another active manager remains and revokes the changed account's tokens. */
    @Test
    void permissionChangeRevokesTokensAndPreservesOtherFields() {
        var manager = UserEntity.builder().id(1L).role(UserRole.MANAGER).status(CommonStatus.ACTIVE).tokenVersion(5).build();
        var other = UserEntity.builder().id(2L).role(UserRole.MANAGER).status(CommonStatus.ACTIVE).build();
        when(users.findAllForAccountUpdate()).thenReturn(java.util.List.of(manager, other));
        var request = new org.involens_be.dto.request.UpdateAccountRequest();
        org.springframework.test.util.ReflectionTestUtils.setField(request, "role", UserRole.EMPLOYEE);
        service.updateAccount(1L, request, UserRole.MANAGER);
        assertEquals(UserRole.EMPLOYEE, manager.getRole());
        assertEquals(CommonStatus.ACTIVE, manager.getStatus());
        assertEquals(6, manager.getTokenVersion());
        verify(users).saveAndFlush(manager);
    }

    /** Resets passwords without changing inactive status and increments the token version. */
    @Test
    void managerResetRevokesAllTargetTokens() {
        var user = UserEntity.builder().id(2L).passwordHash("old-hash").status(CommonStatus.INACTIVE).tokenVersion(3).build();
        when(users.findByIdForUpdate(2L)).thenReturn(java.util.Optional.of(user));
        when(passwordEncoder.encode("NewPassword!")).thenReturn("new-hash");
        service.resetPassword(2L, new org.involens_be.dto.request.ResetPasswordRequest("NewPassword!"), UserRole.MANAGER);
        assertEquals("new-hash", user.getPasswordHash());
        assertEquals(4, user.getTokenVersion());
        assertEquals(CommonStatus.INACTIVE, user.getStatus());
        verify(users).saveAndFlush(user);
    }

    /** Rejects incorrect current passwords and reuse of the existing password without saving changes. */
    @Test
    void passwordFailuresDoNotRevokeTokens() {
        var user = UserEntity.builder().id(2L).passwordHash("old-hash").tokenVersion(3).build();
        when(users.findByIdForUpdate(2L)).thenReturn(java.util.Optional.of(user));
        var request = new org.involens_be.dto.request.ChangePasswordRequest();
        org.springframework.test.util.ReflectionTestUtils.setField(request, "currentPassword", "Wrong!");
        org.springframework.test.util.ReflectionTestUtils.setField(request, "newPassword", "NewPassword!");
        assertThrows(org.involens_be.exception.InvalidAccountOperationException.class,
                () -> service.changePassword(2L, request));
        when(passwordEncoder.matches("NewPassword!", "old-hash")).thenReturn(true);
        assertThrows(org.involens_be.exception.InvalidAccountOperationException.class,
                () -> service.resetPassword(2L, new org.involens_be.dto.request.ResetPasswordRequest("NewPassword!"), UserRole.MANAGER));
        assertEquals(3, user.getTokenVersion());
        verify(users, never()).saveAndFlush(any());
    }

    /** Validates token revocation for changed versions, inactive accounts, stale roles, and legacy tokens. */
    @Test
    void accountTokenValidatorRejectsRevokedTokens() {
        var user = UserEntity.builder().id(2L).email("user@example.com").role(UserRole.EMPLOYEE)
                .status(CommonStatus.ACTIVE).tokenVersion(3).build();
        when(users.findById(2L)).thenReturn(java.util.Optional.of(user));
        var validator = new org.involens_be.config.AccountTokenValidator(users);
        var token = org.springframework.security.oauth2.jwt.Jwt.withTokenValue("test").header("alg", "HS256")
                .subject("user@example.com").claim("userId", 2L).claim("tokenVersion", 3L)
                .claim("role", "EMPLOYEE").build();
        assertFalse(validator.validate(token).hasErrors());
        user.setTokenVersion(4);
        assertTrue(validator.validate(token).hasErrors());
        user.setTokenVersion(3);
        user.setStatus(CommonStatus.INACTIVE);
        assertTrue(validator.validate(token).hasErrors());
        user.setStatus(CommonStatus.ACTIVE);
        user.setRole(UserRole.MANAGER);
        assertTrue(validator.validate(token).hasErrors());
        var legacy = org.springframework.security.oauth2.jwt.Jwt.withTokenValue("test").header("alg", "HS256")
                .subject("user@example.com").claim("userId", 2L).claim("role", "EMPLOYEE").build();
        assertTrue(validator.validate(legacy).hasErrors());
    }

    /** Rejects employee access before reading any account data. */
    @Test
    void employeeCannotListAccounts() {
        assertThrows(AccessDeniedException.class, () -> service.listAccounts(
                new org.involens_be.dto.request.AccountListRequest(), UserRole.EMPLOYEE));
        verifyNoInteractions(users, userMapper);
    }

    /** Returns an empty out-of-range page while retaining the total account count and requested pagination. */
    @Test
    void managerListsEmptyPageWithTotal() {
        var request = new org.involens_be.dto.request.AccountListRequest();
        request.setPage(3);
        request.setPage_size(8);
        var pageable = org.springframework.data.domain.PageRequest.of(2, 8,
                org.springframework.data.domain.Sort.by("id").ascending());
        when(users.findByStatus(CommonStatus.ACTIVE, pageable)).thenReturn(new org.springframework.data.domain.PageImpl<>(
                java.util.List.of(), pageable, 10));
        var response = service.listAccounts(request, UserRole.MANAGER);
        assertAll(() -> assertTrue(response.getItems().isEmpty()),
                () -> assertEquals(10, response.getTotal()),
                () -> assertEquals(3, response.getPage()),
                () -> assertEquals(8, response.getPageSize()));
        verify(users).findByStatus(CommonStatus.ACTIVE, pageable);
    }

    /** Requests only active accounts and delegates conversion to the credential-free account DTO. */
    @Test
    void managerListsOnlyActiveAccounts() {
        var user = UserEntity.builder().id(1L).status(CommonStatus.ACTIVE).role(UserRole.MANAGER).build();
        var item = org.involens_be.dto.response.AccountListItemResponse.builder()
                .id(1L).status(CommonStatus.ACTIVE).role(UserRole.MANAGER).build();
        when(users.findByStatus(eq(CommonStatus.ACTIVE), any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(java.util.List.of(user)));
        when(userMapper.toAccountListItemResponse(user)).thenReturn(item);
        var response = service.listAccounts(new org.involens_be.dto.request.AccountListRequest(), UserRole.MANAGER);
        assertEquals(java.util.List.of(item), response.getItems());
        assertEquals(1, response.getTotal());
    }

    @Mock private UserRepository users;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private RefreshTokenService refreshTokenService;
    @Mock private UserMapper userMapper;
    private AuthService service;

    /** Configures an isolated authentication service for account-creation business tests. */
    @BeforeEach
    void setUp() {
        service = new AuthService(users, passwordEncoder, userMapper, refreshTokenService);
    }

    /** Creates an active account with the selected role and a BCrypt-ready encoded password. */
    @Test
    void managerCreatesActiveAccount() {
        var request = new CreateUserAccountRequest(
                " employee@example.com ", "Password!", " Employee Name ", UserRole.EMPLOYEE);
        when(passwordEncoder.encode("Password!")).thenReturn("encoded-password");
        when(users.save(any(UserEntity.class))).thenAnswer(invocation -> {
            UserEntity user = invocation.getArgument(0);
            user.setId(44L);
            return user;
        });

        service.createAccount(request, UserRole.MANAGER);

        ArgumentCaptor<UserEntity> captor = ArgumentCaptor.forClass(UserEntity.class);
        verify(users).existsByEmailIgnoreCase("employee@example.com");
        verify(users).save(captor.capture());
        UserEntity saved = captor.getValue();
        assertAll(
                () -> assertEquals("employee@example.com", saved.getEmail()),
                () -> assertEquals("encoded-password", saved.getPasswordHash()),
                () -> assertEquals("Employee Name", saved.getFullName()),
                () -> assertEquals(UserRole.EMPLOYEE, saved.getRole()),
                () -> assertEquals(CommonStatus.ACTIVE, saved.getStatus()));
        verify(userMapper).toAuthUserResponse(saved);
    }

    /** Rejects account creation by employees before reading or modifying account data. */
    @Test
    void employeeCannotCreateAccount() {
        var request = new CreateUserAccountRequest(
                "user@example.com", "Password!", "User Name", UserRole.EMPLOYEE);

        assertThrows(AccessDeniedException.class, () -> service.createAccount(request, UserRole.EMPLOYEE));

        verifyNoInteractions(users, passwordEncoder, userMapper);
    }

    /** Returns the approved duplicate-email business error without encoding or saving credentials. */
    @Test
    void duplicateEmailReturnsConflictError() {
        var request = new CreateUserAccountRequest(
                "existing@example.com", "Password!", "Existing User", UserRole.MANAGER);
        when(users.existsByEmailIgnoreCase("existing@example.com")).thenReturn(true);

        assertThrows(EmailAlreadyExistsException.class,
                () -> service.createAccount(request, UserRole.MANAGER));

        verify(users, never()).save(any());
        verifyNoInteractions(passwordEncoder, userMapper);
    }
}
