package org.involens_be.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.involens_be.entity.UserEntity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Slf4j(topic = "JWT-SERVICE")
@Service
@RequiredArgsConstructor
public class JwtService {

    private final JwtEncoder jwtEncoder;

    @Value("${app.jwt.expiration-seconds}")
    private long expirationSeconds;

    @Value("${app.jwt.issuer}")
    private String issuer;

    /**
     * Creates a signed access token containing the authenticated user's identity and role.
     *
     * @param user authenticated user account
     * @return signed JWT access token
     */
    public String generateAccessToken(UserEntity user) {
        log.info("Generating access token. userId={}", user.getId());
        Instant issuedAt = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .issuedAt(issuedAt)
                .expiresAt(issuedAt.plusSeconds(expirationSeconds))
                .subject(user.getEmail())
                .claim("userId", user.getId())
                .claim("role", user.getRole().name())
                .claim("tokenVersion", user.getTokenVersion())
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        log.info("Access token generated successfully. userId={}", user.getId());
        return token;
    }

    /**
     * Returns the configured lifetime of an access token in seconds.
     *
     * @return token lifetime in seconds
     */
    public long getExpirationSeconds() {
        log.debug("Returning access token expiration configuration");
        return expirationSeconds;
    }
}
