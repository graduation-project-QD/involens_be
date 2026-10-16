package org.involens_be.service;

import lombok.extern.slf4j.Slf4j;
import org.involens_be.common.CommonStatus;
import org.involens_be.common.UserRole;
import org.involens_be.entity.RefreshTokenEntity;
import org.involens_be.entity.UserEntity;
import org.involens_be.exception.InvalidCredentialsException;
import org.involens_be.mapper.UserMapper;
import org.involens_be.repository.RefreshTokenRepository;
import org.involens_be.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Verifies single-use refresh credentials, account revocation and independent device sessions. */
@Slf4j
class RefreshTokenServiceTest {
    private RefreshTokenRepository tokens;
    private UserRepository users;
    private JwtService jwtService;
    private RefreshTokenService service;
    private UserEntity user;
    private ConcurrentHashMap<String, RefreshTokenEntity> stored;

    /** Prepares an isolated token store and account with production token generation and hashing. */
    @BeforeEach
    void setUp() {
        log.info("Preparing refresh token tests");
        tokens = mock(RefreshTokenRepository.class);
        users = mock(UserRepository.class);
        jwtService = mock(JwtService.class);
        service = new RefreshTokenService(tokens, users, jwtService, mock(UserMapper.class));
        ReflectionTestUtils.setField(service, "refreshExpirationSeconds", 604800L);
        user = UserEntity.builder().id(7L).email("employee@example.com").role(UserRole.EMPLOYEE)
                .status(CommonStatus.ACTIVE).tokenVersion(3).build();
        stored = new ConcurrentHashMap<>();
        when(tokens.save(any())).thenAnswer(invocation -> {
            RefreshTokenEntity token = invocation.getArgument(0);
            stored.put(token.getTokenHash(), token);
            return token;
        });
        when(tokens.findByTokenHashForUpdate(anyString())).thenAnswer(
                invocation -> Optional.ofNullable(stored.get(invocation.getArgument(0))));
        when(users.findByIdForUpdate(7L)).thenReturn(Optional.of(user));
        when(jwtService.generateAccessToken(user)).thenReturn("access-token");
        when(jwtService.getExpirationSeconds()).thenReturn(3600L);
    }

    /** Computes an independent expected lookup hash for inspecting persisted credential records. */
    private String hash(String token) throws Exception {
        log.debug("Computing test refresh credential hash");
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(token.getBytes(StandardCharsets.UTF_8)));
    }

    /** Stores only a hash, generates independent credentials and returns approved lifetimes. */
    @Test
    void issuesIndependentHashedCredentials() throws Exception {
        log.info("Testing refresh token issue and secure persistence");
        Instant before = Instant.now();
        var first = service.issue(user);
        var second = service.issue(user);
        assertNotEquals(first.getRefreshToken(), second.getRefreshToken());
        assertEquals(43, first.getRefreshToken().length());
        assertEquals(3600, first.getExpiresIn());
        assertEquals(604800, first.getRefreshExpiresIn());
        assertEquals("access-token", first.getAccessToken());
        assertEquals(2, stored.size());
        var record = stored.get(hash(first.getRefreshToken()));
        assertNotNull(record);
        assertNotEquals(first.getRefreshToken(), record.getTokenHash());
        assertEquals(7L, record.getUserId());
        assertEquals(3, record.getTokenVersion());
        assertFalse(record.isRevoked());
        assertFalse(record.getExpiresAt().isBefore(before.plusSeconds(604800)));
        assertFalse(record.getExpiresAt().isAfter(Instant.now().plusSeconds(604800)));
    }

    /** Replaces the old credential once and rejects replays while keeping its replacement usable. */
    @Test
    void rotatesAndRejectsReusedToken() throws Exception {
        log.info("Testing refresh rotation and replay rejection");
        var original = service.issue(user);
        var rotated = service.refresh(original.getRefreshToken());
        assertNotEquals(original.getRefreshToken(), rotated.getRefreshToken());
        assertTrue(stored.get(hash(original.getRefreshToken())).isRevoked());
        assertFalse(stored.get(hash(rotated.getRefreshToken())).isRevoked());
        assertThrows(InvalidCredentialsException.class, () -> service.refresh(original.getRefreshToken()));
        assertDoesNotThrow(() -> service.refresh(rotated.getRefreshToken()));
    }

    /** Rejects expired, unknown and missing credentials without issuing replacements. */
    @Test
    void rejectsUnavailableCredentials() throws Exception {
        log.info("Testing invalid and expired refresh credentials");
        var original = service.issue(user);
        stored.get(hash(original.getRefreshToken())).setExpiresAt(Instant.now().minusSeconds(1));
        clearInvocations(jwtService);
        for (String value : new String[]{original.getRefreshToken(), "unknown", "", " ", null}) {
            assertThrows(InvalidCredentialsException.class, () -> service.refresh(value));
        }
        verifyNoInteractions(jwtService);
        assertEquals(1, stored.size());
    }

    /** Password and permission token-version changes invalidate old refresh credentials permanently. */
    @Test
    void rejectsChangedAccountVersionAndInactiveAccount() {
        log.info("Testing refresh account revocation");
        var original = service.issue(user);
        user.setTokenVersion(4);
        assertThrows(InvalidCredentialsException.class, () -> service.refresh(original.getRefreshToken()));
        user.setStatus(CommonStatus.INACTIVE);
        assertThrows(InvalidCredentialsException.class, () -> service.refresh(original.getRefreshToken()));
        user.setStatus(CommonStatus.ACTIVE);
        assertThrows(InvalidCredentialsException.class, () -> service.refresh(original.getRefreshToken()));
        assertEquals(1, stored.size());
    }

    /** Inactive and missing accounts cannot exchange otherwise valid refresh credentials. */
    @Test
    void rejectsInactiveAndMissingAccount() {
        log.info("Testing unavailable refresh account");
        var original = service.issue(user);
        user.setStatus(CommonStatus.INACTIVE);
        assertThrows(InvalidCredentialsException.class, () -> service.refresh(original.getRefreshToken()));
        when(users.findByIdForUpdate(7L)).thenReturn(Optional.empty());
        assertThrows(InvalidCredentialsException.class, () -> service.refresh(original.getRefreshToken()));
    }

    /** Logout revokes only the supplied device credential and does not change access-token authorization. */
    @Test
    void logoutPreservesOtherDeviceSessions() {
        log.info("Testing independent session logout");
        var first = service.issue(user);
        var second = service.issue(user);
        service.revoke(first.getRefreshToken(), 7L);
        assertThrows(InvalidCredentialsException.class, () -> service.refresh(first.getRefreshToken()));
        assertDoesNotThrow(() -> service.refresh(second.getRefreshToken()));
        assertEquals(3, user.getTokenVersion());
        verify(users, never()).save(any());
    }

    /** A valid bearer user cannot revoke a different user's refresh session. */
    @Test
    void logoutRejectsOtherUsersCredential() throws Exception {
        log.info("Testing refresh logout ownership");
        var original = service.issue(user);
        when(users.findByIdForUpdate(8L)).thenReturn(Optional.of(UserEntity.builder().id(8L)
                .status(CommonStatus.ACTIVE).tokenVersion(3).build()));
        assertThrows(InvalidCredentialsException.class, () -> service.revoke(original.getRefreshToken(), 8L));
        assertFalse(stored.get(hash(original.getRefreshToken())).isRevoked());
    }

    /** Rolls back rotation if issuing its replacement access token fails, keeping database changes atomic. */
    @Test
    void failedReplacementRollsBackRotation() {
        log.info("Testing transaction rollback after token issuance failure");
        var original = service.issue(user);
        when(jwtService.generateAccessToken(user)).thenThrow(new IllegalStateException("Signing unavailable"));
        var manager = mock(org.springframework.transaction.PlatformTransactionManager.class);
        var status = new org.springframework.transaction.support.SimpleTransactionStatus();
        when(manager.getTransaction(any())).thenReturn(status);
        var factory = new org.springframework.aop.framework.ProxyFactory(service);
        factory.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(manager,
                new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
        var transactionalService = (RefreshTokenService) factory.getProxy();
        assertThrows(IllegalStateException.class, () -> transactionalService.refresh(original.getRefreshToken()));
        verify(manager).rollback(status);
        verify(manager, never()).commit(any());
    }
}
