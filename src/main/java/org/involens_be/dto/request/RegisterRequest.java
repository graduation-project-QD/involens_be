package org.involens_be.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** Accepts public registration details without any client-selected role or account status. */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Slf4j
public class RegisterRequest {
    @NotBlank(message = "Email is required")
    @Email(message = "Email is invalid")
    private String email;

    @NotBlank(message = "Password is required")
    @Size(min = 8, message = "Password must contain at least 8 characters")
    @Pattern(regexp = ".*[A-Z].*", message = "Password must contain at least one uppercase letter")
    @Pattern(regexp = ".*[!@#$%^&*()_+\\-=\\[\\]{};':\"\\\\|,.<>/?].*",
            message = "Password must contain at least one special character")
    @Pattern(regexp = "\\S+", message = "Password must not contain whitespace")
    private String password;

    @NotBlank(message = "Full name is required")
    private String fullName;

    @NotBlank(message = "Password confirmation is required")
    private String confirmPassword;

    /** Confirms the repeated password matches exactly; field constraints handle missing passwords. */
    @AssertTrue(message = "Password confirmation must match password")
    @JsonIgnore
    public boolean isPasswordConfirmed() {
        log.debug("Validating registration password confirmation");
        boolean matches = password == null || confirmPassword == null || password.equals(confirmPassword);
        if (!matches) {
            log.warn("Registration password confirmation does not match");
        }
        return matches;
    }
}
