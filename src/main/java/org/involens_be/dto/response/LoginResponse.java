package org.involens_be.dto.response;

import com.google.gson.annotations.Expose;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoginResponse {

    @Expose
    private String accessToken;

    @Expose
    private long expiresIn;

    @Expose
    private String refreshToken;

    @Expose
    private long refreshExpiresIn;

    @Expose
    private AuthUserResponse user;
}
