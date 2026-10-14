package org.involens_be.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.involens_be.dto.request.LoginRequest;
import org.involens_be.dto.request.CreateUserAccountRequest;
import org.involens_be.dto.response.AuthUserResponse;
import org.involens_be.dto.response.LoginResponse;
import org.involens_be.entity.UserEntity;
import org.involens_be.common.UserRole;
import org.involens_be.common.CommonStatus;
import org.involens_be.exception.InvalidCredentialsException;
import org.involens_be.exception.EmailAlreadyExistsException;
import org.involens_be.mapper.UserMapper;
import org.involens_be.repository.UserRepository;
import org.involens_be.dto.request.AccountListRequest;
import org.involens_be.dto.response.AccountListResponse;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.Authentication;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.involens_be.dto.request.UpdateAccountRequest;
import org.involens_be.dto.request.ChangePasswordRequest;
import org.involens_be.dto.request.ResetPasswordRequest;
import org.involens_be.dto.response.AccountListItemResponse;
import org.involens_be.exception.AccountNotFoundException;
import org.involens_be.exception.InvalidAccountOperationException;

import java.util.List;

@Slf4j(topic = "AUTH-SERVICE")
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final UserMapper userMapper;

    /** Returns management information for an active account after checking manager access. */
    public AccountListItemResponse getAccount(Long id, UserRole role) {
        log.info("Starting account detail lookup. userId={}", id);
        requireManager(role);
        var user = userRepository.findByIdAndStatus(id, CommonStatus.ACTIVE)
                .orElseThrow(AccountNotFoundException::new);
        log.info("Account detail loaded. userId={}", id);
        return userMapper.toAccountListItemResponse(user);
    }

    /** Updates supplied role/status fields while preserving an active manager and revoking changed account sessions. */
    @Transactional
    public AccountListItemResponse updateAccount(Long id, UpdateAccountRequest request, UserRole role) {
        log.info("Starting account permission update. userId={}", id);
        requireManager(role);
        if (request.getRole() == null && request.getStatus() == null) {
            log.warn("Account permission update rejected because no fields were supplied. userId={}", id);
            throw new InvalidAccountOperationException("At least one of role or status is required");
        }
        var accounts = userRepository.findAllForAccountUpdate();
        var user = accounts.stream().filter(account -> account.getId().equals(id))
                .findFirst().orElseThrow(AccountNotFoundException::new);
        UserRole newRole = request.getRole() == null ? user.getRole() : request.getRole();
        CommonStatus newStatus = request.getStatus() == null ? user.getStatus() : request.getStatus();
        requireActiveManagerRemains(user, newRole, newStatus, accounts);
        if (newRole != user.getRole() || newStatus != user.getStatus()) {
            user.setRole(newRole);
            user.setStatus(newStatus);
            user.setTokenVersion(user.getTokenVersion() + 1);
            userRepository.saveAndFlush(user);
            log.info("Account permissions changed and sessions revoked. userId={}", id);
        }
        log.info("Account permission update completed. userId={}", id);
        return userMapper.toAccountListItemResponse(user);
    }

    /** Soft-deletes an account for a manager and revokes its sessions; repeated deletion succeeds without changes. */
    @Transactional
    public void deleteAccount(Long id, UserRole role) {
        log.info("Starting account soft deletion. userId={}", id);
        requireManager(role);
        var accounts = userRepository.findAllForAccountUpdate();
        var user = accounts.stream().filter(account -> account.getId().equals(id))
                .findFirst().orElseThrow(AccountNotFoundException::new);
        if (user.getStatus() == CommonStatus.INACTIVE) {
            log.info("Account soft deletion completed: account is already inactive. userId={}", id);
            return;
        }
        requireActiveManagerRemains(user, user.getRole(), CommonStatus.INACTIVE, accounts);
        user.setStatus(CommonStatus.INACTIVE);
        user.setTokenVersion(user.getTokenVersion() + 1);
        userRepository.saveAndFlush(user);
        log.info("Account soft deletion completed and sessions revoked. userId={}", id);
    }

    /** Rejects role or status changes that would remove the final active manager from the locked account set. */
    private void requireActiveManagerRemains(UserEntity user, UserRole newRole, CommonStatus newStatus,
            List<UserEntity> accounts) {
        log.debug("Checking that an active manager remains after account change. userId={}", user.getId());
        if (user.getRole() == UserRole.MANAGER && user.getStatus() == CommonStatus.ACTIVE
                && (newRole != UserRole.MANAGER || newStatus != CommonStatus.ACTIVE)
                && accounts.stream().noneMatch(account -> !account.getId().equals(user.getId())
                        && account.getRole() == UserRole.MANAGER && account.getStatus() == CommonStatus.ACTIVE)) {
            log.warn("Account change rejected because it would remove the last active manager. userId={}", user.getId());
            throw new InvalidAccountOperationException("Cannot deactivate or demote the last active manager");
        }
        log.debug("Active manager constraint satisfied. userId={}", user.getId());
    }

    /** Changes the caller's password after verifying current credentials and revokes every existing session. */
    @Transactional
    public void changePassword(Long id, ChangePasswordRequest request) {
        log.info("Starting self-service password change. userId={}", id);
        var user = userRepository.findByIdForUpdate(id).orElseThrow(AccountNotFoundException::new);
        if (!passwordEncoder.matches(request.getCurrentPassword(), user.getPasswordHash())) {
            throw new InvalidAccountOperationException("Current password is incorrect");
        }
        replacePassword(user, request.getNewPassword());
        log.info("Self-service password change completed. userId={}", id);
    }

    /** Allows a manager to reset any existing account's password without requiring its current password. */
    @Transactional
    public void resetPassword(Long id, ResetPasswordRequest request, UserRole role) {
        log.info("Starting manager password reset. userId={}", id);
        requireManager(role);
        var user = userRepository.findByIdForUpdate(id).orElseThrow(AccountNotFoundException::new);
        replacePassword(user, request.getNewPassword());
        log.info("Manager password reset completed. userId={}", id);
    }

    /** Persists a different encoded password and a new token version as part of the caller's transaction. */
    private void replacePassword(UserEntity user, String newPassword) {
        log.debug("Replacing account password. userId={}", user.getId());
        if (passwordEncoder.matches(newPassword, user.getPasswordHash())) {
            throw new InvalidAccountOperationException("New password must differ from the current password");
        }
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setTokenVersion(user.getTokenVersion() + 1);
        userRepository.saveAndFlush(user);
        log.info("Password replaced and sessions revoked. userId={}", user.getId());
    }

    /** Enforces manager-only access before account management reads or writes. */
    private void requireManager(UserRole role) {
        log.debug("Checking manager account access. role={}", role);
        if (role != UserRole.MANAGER) {
            log.warn("Account management rejected for non-manager. role={}", role);
            throw new AccessDeniedException("Only managers can manage accounts");
        }
    }

    /** Loads an id-ordered page of active accounts across all roles after verifying manager access. */
    public AccountListResponse listAccounts(AccountListRequest request, UserRole callerRole) {
        log.info("Starting account listing. role={}, page={}, pageSize={}",
                callerRole, request.getPage(), request.getPage_size());
        if (callerRole != UserRole.MANAGER) {
            log.warn("Account listing rejected because caller is not a manager. role={}", callerRole);
            throw new AccessDeniedException("Only managers can list accounts");
        }
        try {
            var accounts = userRepository.findByStatus(CommonStatus.ACTIVE, PageRequest.of(
                    request.getPage() - 1, request.getPage_size(),
                    Sort.by("id").ascending()));
            var response = AccountListResponse.builder()
                    .items(accounts.getContent().stream().map(userMapper::toAccountListItemResponse).toList())
                    .total(accounts.getTotalElements())
                    .page(request.getPage()).pageSize(request.getPage_size()).build();
            log.info("Account listing completed. returned={}, total={}",
                    response.getItems().size(), response.getTotal());
            return response;
        } catch (RuntimeException exception) {
            log.error("Account listing failed. page={}, pageSize={}",
                    request.getPage(), request.getPage_size(), exception);
            throw exception;
        }
    }

    /**
     * Creates an active account with manager-supplied credentials and role.
     *
     * @param request validated account information supplied by the manager
     * @param creatorRole role embedded in the authenticated manager's token
     * @return public information for the newly persisted account
     */
    public AuthUserResponse createAccount(CreateUserAccountRequest request, UserRole creatorRole) {
        String email = request.getEmail().trim();
        log.info("Starting manager account creation. email={}, requestedRole={}, creatorRole={}",
                email, request.getRole(), creatorRole);
        if (creatorRole != UserRole.MANAGER) {
            log.warn("Account creation rejected because caller is not a manager. creatorRole={}", creatorRole);
            throw new AccessDeniedException("Only managers can create accounts");
        }
        if (userRepository.existsByEmailIgnoreCase(email)) {
            log.warn("Account creation rejected because email already exists. email={}", email);
            throw new EmailAlreadyExistsException();
        }

        UserEntity user = UserEntity.builder()
                .email(email)
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .fullName(request.getFullName().trim())
                .role(request.getRole())
                .status(CommonStatus.ACTIVE)
                .build();
        UserEntity savedUser = userRepository.save(user);
        log.info("Manager account creation completed successfully. userId={}, role={}",
                savedUser.getId(), savedUser.getRole());
        return userMapper.toAuthUserResponse(savedUser);
    }

    /**
     * Authenticates a user by email and password and returns a new JWT access token.
     *
     * @param request submitted login credentials
     * @return access token details and authenticated user profile
     */
    public LoginResponse login(LoginRequest request) {
        String email = request.getEmail().trim();
        log.info("Starting user authentication. email={}", email);
        UserEntity user = userRepository.findByEmailIgnoreCase(email)
                .orElseThrow(() -> {
                    log.warn("Authentication failed because the email was not found. email={}", email);
                    return new InvalidCredentialsException();
                });

        if (user.getStatus() == CommonStatus.INACTIVE) {
            log.warn("Authentication failed because the account is inactive. userId={}", user.getId());
            throw new InvalidCredentialsException("Account not found");
        }
        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            log.warn("Authentication failed because the password did not match. userId={}", user.getId());
            throw new InvalidCredentialsException();
        }

        LoginResponse response = LoginResponse.builder()
                .accessToken(jwtService.generateAccessToken(user))
                .expiresIn(jwtService.getExpirationSeconds())
                .user(userMapper.toAuthUserResponse(user))
                .build();
        log.info("User authenticated successfully. userId={}", user.getId());
        return response;
    }

    /**
     * Loads the profile belonging to the bearer token's authenticated subject.
     *
     * @param authentication validated JWT authentication
     * @return current user's public profile
     */
    public AuthUserResponse getCurrentUser(Authentication authentication) {
        String email = authentication.getName();
        log.info("Loading current authenticated user. email={}", email);
        UserEntity user = userRepository.findByEmailIgnoreCase(email)
                .orElseThrow(() -> {
                    log.warn("Authenticated user no longer exists. email={}", email);
                    return new InvalidCredentialsException();
                });
        if (user.getStatus() == CommonStatus.INACTIVE) {
            log.warn("Current-user request rejected because the account is inactive. userId={}", user.getId());
            throw new InvalidCredentialsException("Account not found");
        }
        log.info("Current authenticated user loaded successfully. userId={}", user.getId());
        return userMapper.toAuthUserResponse(user);
    }
}
