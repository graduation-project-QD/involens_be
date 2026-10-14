package org.involens_be.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.involens_be.common.UserRole;
import org.involens_be.common.CommonStatus;
import org.involens_be.entity.UserEntity;
import org.involens_be.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@Profile("dev")
@RequiredArgsConstructor
public class AdminAccountInitializer implements ApplicationRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${app.admin.email:}")
    private String email;

    @Value("${app.admin.password:}")
    private String password;

    @Value("${app.admin.full-name:}")
    private String fullName;

    /**
     * Creates the configured active manager account on development startup when its email
     * is absent, preserving existing accounts and allowing startup to continue on failure.
     * Passwords are encoded with the application's password encoder before persistence.
     *
     * @param args application startup arguments
     */
    @Override
    public void run(ApplicationArguments args) {
        log.info("Starting development admin account initialization");
        try {
            if (email.isBlank()) {
                log.error("Development admin initialization skipped: ADMIN_EMAIL is required");
                return;
            }

            String normalizedEmail = email.trim();
            if (userRepository.existsByEmailIgnoreCase(normalizedEmail)) {
                log.info("Development admin initialization skipped: configured account already exists");
                return;
            }

            if (password.isBlank() || fullName.isBlank()) {
                log.error("Development admin initialization skipped: ADMIN_PASSWORD and ADMIN_FULL_NAME are required");
                return;
            }

            UserEntity user = UserEntity.builder()
                    .email(normalizedEmail)
                    .passwordHash(passwordEncoder.encode(password))
                    .fullName(fullName.trim())
                    .role(UserRole.MANAGER)
                    .status(CommonStatus.ACTIVE)
                    .build();
            UserEntity savedUser = userRepository.saveAndFlush(user);
            log.info("Development admin account created successfully. userId={}, role={}",
                    savedUser.getId(), savedUser.getRole());
        } catch (Exception exception) {
            log.error("Development admin account initialization failed; application startup will continue", exception);
        }
    }
}
