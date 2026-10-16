package org.involens_be.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.involens_be.common.CommonStatus;
import org.involens_be.dto.response.LoginResponse;
import org.involens_be.entity.RefreshTokenEntity;
import org.involens_be.entity.UserEntity;
import org.involens_be.exception.InvalidCredentialsException;
import org.involens_be.mapper.UserMapper;
import org.involens_be.repository.RefreshTokenRepository;
import org.involens_be.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;

/** Issues, rotates and revokes per-session refresh credentials using current account authorization. */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefreshTokenService {
    private final RefreshTokenRepository tokens;
    private final UserRepository users;
    private final JwtService jwtService;
    private final UserMapper userMapper;
    private final SecureRandom random = new SecureRandom();

    @Value("${app.jwt.refresh-expiration-seconds}")
    private long refreshExpirationSeconds;

    /** Creates an independent login session and returns its plaintext credential only to the client. */
    @Transactional
    public LoginResponse issue(UserEntity user) {
        log.info("Issuing authentication token pair. userId={}", user.getId());
        if (refreshExpirationSeconds <= 0) {
            log.error("Refresh token lifetime must be positive");
            throw new IllegalStateException("Refresh token lifetime must be positive");
        }
        byte[] secret = new byte[32];
        random.nextBytes(secret);
        String refreshToken = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        tokens.save(RefreshTokenEntity.builder().tokenHash(hash(refreshToken)).userId(user.getId())
                .tokenVersion(user.getTokenVersion()).expiresAt(Instant.now().plusSeconds(refreshExpirationSeconds))
                .revoked(false).build());
        LoginResponse response = LoginResponse.builder()
                .accessToken(jwtService.generateAccessToken(user)).expiresIn(jwtService.getExpirationSeconds())
                .refreshToken(refreshToken).refreshExpiresIn(refreshExpirationSeconds)
                .user(userMapper.toAuthUserResponse(user)).build();
        log.info("Authentication token pair issued. userId={}", user.getId());
        return response;
    }

    /** Consumes a valid refresh credential once and atomically replaces it with a new seven-day session credential. */
    @Transactional
    public LoginResponse refresh(String refreshToken) {
        log.info("Starting refresh token rotation");
        RefreshTokenEntity token = requireUsableToken(refreshToken);
        UserEntity user = users.findByIdForUpdate(token.getUserId()).orElseThrow(this::invalidToken);
        if (user.getStatus() != CommonStatus.ACTIVE || user.getTokenVersion() != token.getTokenVersion()) {
            throw invalidToken();
        }
        token.setRevoked(true);
        tokens.save(token);
        LoginResponse response = issue(user);
        log.info("Refresh token rotation completed. userId={}", user.getId());
        return response;
    }

    /** Revokes only the supplied active refresh credential after verifying it belongs to the bearer user. */
    @Transactional
    public void revoke(String refreshToken, Long userId) {
        log.info("Starting refresh token logout. userId={}", userId);
        RefreshTokenEntity token = requireUsableToken(refreshToken);
        UserEntity user = users.findByIdForUpdate(userId).orElseThrow(this::invalidToken);
        if (!token.getUserId().equals(userId) || user.getStatus() != CommonStatus.ACTIVE
                || user.getTokenVersion() != token.getTokenVersion()) {
            throw invalidToken();
        }
        token.setRevoked(true);
        tokens.save(token);
        log.info("Current session refresh token revoked. userId={}", userId);
    }

    /** Locks the hashed credential and rejects missing, expired or previously consumed tokens without revealing secrets. */
    private RefreshTokenEntity requireUsableToken(String refreshToken) {
        log.debug("Validating refresh credential");
        if (refreshToken == null || refreshToken.isBlank()) {
            throw invalidToken();
        }
        RefreshTokenEntity token = tokens.findByTokenHashForUpdate(hash(refreshToken)).orElseThrow(this::invalidToken);
        if (token.isRevoked() || !token.getExpiresAt().isAfter(Instant.now())) {
            throw invalidToken();
        }
        return token;
    }

    /** Computes the irreversible database lookup key for a high-entropy refresh credential. */
    private String hash(String refreshToken) {
        log.debug("Computing refresh credential lookup hash");
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(refreshToken.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            log.error("Refresh token hashing is unavailable", exception);
            throw new IllegalStateException("Refresh token hashing is unavailable", exception);
        }
    }

    /** Reports a uniform authentication failure for refresh credentials and unavailable accounts. */
    private InvalidCredentialsException invalidToken() {
        log.warn("Refresh credential rejected: invalid, expired, revoked or account unavailable");
        return new InvalidCredentialsException("Refresh token is invalid or expired");
    }
}
