package org.involens_be.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.involens_be.common.CommonStatus;
import org.involens_be.repository.UserRepository;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class AccountTokenValidator implements OAuth2TokenValidator<Jwt> {
    private final UserRepository userRepository;

    /** Rejects revoked tokens and tokens for missing, inactive, or changed accounts on every authenticated request. */
    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        log.debug("Validating account token. subject={}", jwt.getSubject());
        try {
            Object idClaim = jwt.getClaim("userId");
            Object versionClaim = jwt.getClaim("tokenVersion");
            if (idClaim instanceof Number id && versionClaim instanceof Number version) {
                var account = userRepository.findById(id.longValue());
                if (account.isPresent()) {
                    var user = account.get();
                    if (user.getStatus() == CommonStatus.ACTIVE
                            && user.getTokenVersion() == version.longValue()
                            && user.getEmail().equals(jwt.getSubject())
                            && user.getRole().name().equals(jwt.getClaimAsString("role"))) {
                        log.debug("Account token accepted. userId={}", user.getId());
                        return OAuth2TokenValidatorResult.success();
                    }
                }
            }
            log.warn("Account token rejected: revoked or account unavailable. subject={}", jwt.getSubject());
        } catch (RuntimeException exception) {
            log.error("Account token validation failed. subject={}", jwt.getSubject(), exception);
        }
        return OAuth2TokenValidatorResult.failure(
                new OAuth2Error("invalid_token", "Account token is no longer valid", null));
    }
}
