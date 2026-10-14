package org.involens_be.mapper;

import org.involens_be.dto.response.AuthUserResponse;
import org.involens_be.dto.response.AccountListItemResponse;
import org.involens_be.entity.UserEntity;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface UserMapper {

    /** Maps a persisted user account to the public user representation returned by authentication APIs. */
    AuthUserResponse toAuthUserResponse(UserEntity user);

    /** Maps account management information without exposing stored credentials. */
    AccountListItemResponse toAccountListItemResponse(UserEntity user);
}
