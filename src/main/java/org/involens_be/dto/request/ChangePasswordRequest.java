package org.involens_be.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class ChangePasswordRequest extends ResetPasswordRequest {
    @NotBlank(message = "Current password is required")
    private String currentPassword;
}
