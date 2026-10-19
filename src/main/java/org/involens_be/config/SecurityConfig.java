package org.involens_be.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Slf4j
@Configuration
public class SecurityConfig {

    private static final int MINIMUM_SECRET_BYTES = 32;

    /**
     * Configures stateless API authorization, permits login, and protects all other endpoints.
     *
     * @param http Spring Security HTTP configuration
     * @return configured security filter chain
     * @throws Exception when Spring Security cannot build the filter chain
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        log.info("Configuring stateless JWT security filter chain");
        http
                .cors(Customizer.withDefaults())
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(
                                "/api/v1/auth/login",
                                "/api/v1/auth/refresh-token",
                                "/ws",
                                "/actuator/health",
                                "/actuator/health/**",
                                "/v3/api-docs/**",
                                "/v3/api-docs.yaml",
                                "/swagger-ui/**",
                                "/swagger-ui.html")
                        .permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()));
        SecurityFilterChain filterChain = http.build();
        log.info("Stateless JWT security filter chain configured successfully");
        return filterChain;
    }

    /**
     * Allows the local frontend to call APIs with authorization headers and supported
     * HTTP methods, including preflight requests, without cookie credentials.
     *
     * @return CORS configuration applied to all application endpoints
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        log.info("Configuring CORS for frontend origin http://3.25.226.163");
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(List.of(
                "http://localhost:5173",
                "http://3.25.226.163"
        ));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(false);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        log.info("Frontend CORS configured successfully without cookie credentials");
        return source;
    }

    /**
     * Provides BCrypt password verification for stored user password hashes.
     *
     * @return BCrypt password encoder
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        log.info("Configuring BCrypt password encoder");
        return new BCryptPasswordEncoder();
    }

    /**
     * Provides the component responsible for signing application-issued JWTs.
     *
     * @param secret configured JWT signing secret
     * @return symmetric JWT encoder
     */
    @Bean
    public JwtEncoder jwtEncoder(@Value("${app.jwt.secret}") String secret) {
        log.info("Configuring JWT encoder");
        return NimbusJwtEncoder.withSecretKey(createSecretKey(secret))
                .algorithm(MacAlgorithm.HS256)
                .build();
    }

    /**
     * Provides the component responsible for validating incoming JWT signatures and claims.
     *
     * @param secret configured JWT signing secret
     * @return symmetric JWT decoder
     */
    @Bean
    public JwtDecoder jwtDecoder(
            @Value("${app.jwt.secret}") String secret,
            @Value("${app.jwt.issuer}") String issuer,
            AccountTokenValidator accountTokenValidator) {
        log.info("Configuring JWT decoder");
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(createSecretKey(secret))
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        OAuth2TokenValidator<Jwt> validator = JwtValidators.createDefaultWithIssuer(issuer);
        decoder.setJwtValidator(new org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator<>(
                validator, accountTokenValidator));
        log.info("JWT decoder configured successfully with issuer validation");
        return decoder;
    }

    /**
     * Converts and validates the configured signing secret before cryptographic use.
     *
     * @param secret raw secret supplied through configuration
     * @return HMAC SHA-256 secret key
     */
    private SecretKey createSecretKey(String secret) {
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < MINIMUM_SECRET_BYTES) {
            log.error("JWT secret configuration is shorter than {} bytes", MINIMUM_SECRET_BYTES);
            throw new IllegalStateException("JWT_SECRET must contain at least 32 bytes");
        }
        log.debug("JWT secret configuration validated successfully");
        return new SecretKeySpec(keyBytes, "HmacSHA256");
    }
}
