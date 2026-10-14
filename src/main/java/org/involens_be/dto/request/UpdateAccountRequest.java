package org.involens_be.dto.request;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.involens_be.common.UserRole;
import org.involens_be.common.CommonStatus;

@Getter
@NoArgsConstructor
public class UpdateAccountRequest {
    @JsonSetter(nulls = Nulls.FAIL)
    private UserRole role;

    @JsonSetter(nulls = Nulls.FAIL)
    private CommonStatus status;
}
