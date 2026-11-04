package com.picsou.repository;

import com.picsou.model.AppUser;
import com.picsou.model.UserRole;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {
    Optional<AppUser> findByUsername(String username);
    boolean existsByUsername(String username);

    long countByRole(UserRole role);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM AppUser u WHERE u.id = :id")
    Optional<AppUser> findByIdForUpdate(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM AppUser u WHERE u.role = :role")
    List<AppUser> findAllByRoleForUpdate(@Param("role") UserRole role);

    @Query("SELECT u FROM AppUser u JOIN FETCH u.member WHERE u.id = :id")
    Optional<AppUser> findByIdWithMember(Long id);

    @Query("SELECT u FROM AppUser u JOIN FETCH u.member WHERE u.username = :username")
    Optional<AppUser> findByUsernameWithMember(String username);

    Optional<AppUser> findByActivationToken(String token);

    Optional<AppUser> findByMemberId(Long memberId);
}
