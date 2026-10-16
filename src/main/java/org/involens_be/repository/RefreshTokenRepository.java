package org.involens_be.repository;

import jakarta.persistence.LockModeType;
import org.involens_be.entity.RefreshTokenEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository extends JpaRepository<RefreshTokenEntity, UUID> {
    /** Serializes rotation and logout so only one request can consume a refresh credential. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from RefreshTokenEntity t where t.tokenHash = :hash")
    Optional<RefreshTokenEntity> findByTokenHashForUpdate(@Param("hash") String hash);
}
