package org.involens_be.dto.response;

import com.google.gson.annotations.Expose;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.involens_be.common.UserRole;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuthUserResponse {

    @Expose
    private Long id;

    @Expose
    private String email;

    @Expose
    private String fullName;

    @Expose
    private UserRole role;
}
