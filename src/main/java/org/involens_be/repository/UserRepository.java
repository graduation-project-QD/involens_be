package org.involens_be.repository;

import org.involens_be.entity.UserEntity;
import org.involens_be.common.CommonStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<UserEntity, Long> {

    /** Finds a management-visible account with the requested lifecycle status. */
    Optional<UserEntity> findByIdAndStatus(Long id, CommonStatus status);

    /** Pages accounts of the requested lifecycle status, excluding soft-deleted accounts from totals. */
    Page<UserEntity> findByStatus(CommonStatus status, Pageable pageable);

    /** Serializes account permission changes to preserve at least one active manager. */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select u from UserEntity u order by u.id")
    java.util.List<UserEntity> findAllForAccountUpdate();

    /** Locks the target account so password changes and token revocation commit together. */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select u from UserEntity u where u.id = :id")
    Optional<UserEntity> findByIdForUpdate(@org.springframework.data.repository.query.Param("id") Long id);

    /**
     * Finds the user account associated with the supplied login email.
     *
     * @param email normalized email address used for authentication
     * @return matching user when one exists
     */
    Optional<UserEntity> findByEmailIgnoreCase(String email);

    /**
     * Checks whether an account already uses the supplied email, ignoring letter case.
     *
     * @param email normalized email proposed for a new account
     * @return true when the email is already registered
     */
    boolean existsByEmailIgnoreCase(String email);
}
