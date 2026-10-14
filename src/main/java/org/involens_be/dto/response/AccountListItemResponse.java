package org.involens_be.dto.response;

import lombok.Builder;
import com.google.gson.annotations.Expose;
import lombok.Getter;
import org.involens_be.common.UserRole;
import org.involens_be.common.CommonStatus;
import java.time.LocalDateTime;

@Getter
@Builder
public class AccountListItemResponse {
    @Expose
    private Long id;
    @Expose
    private String email;
    @Expose
    private String fullName;
    @Expose
    private UserRole role;
    @Expose
    private CommonStatus status;
    @Expose
    private LocalDateTime createdAt;
    @Expose
    private LocalDateTime updatedAt;
}
