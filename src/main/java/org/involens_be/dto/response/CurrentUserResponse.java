package org.involens_be.dto.response;

import com.google.gson.annotations.Expose;
import lombok.Builder;
import lombok.Getter;
import java.time.LocalDateTime;

/** Exposes the current user's profile and account creation time without their authorization role. */
@Getter
@Builder
public class CurrentUserResponse {
    @Expose
    private Long id;
    @Expose
    private String email;
    @Expose
    private String fullName;
    @Expose
    private LocalDateTime createdAt;
}
