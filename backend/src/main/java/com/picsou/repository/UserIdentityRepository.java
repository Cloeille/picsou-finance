package com.picsou.repository;

import com.picsou.model.UserIdentity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface UserIdentityRepository extends JpaRepository<UserIdentity, Long> {

    Optional<UserIdentity> findByIssuerAndSubject(String issuer, String subject);

    List<UserIdentity> findByUserIdOrderByCreatedAtAsc(Long userId);

    Optional<UserIdentity> findByIdAndUserId(Long id, Long userId);

    long countByUserId(Long userId);

    boolean existsByUserIdAndLastLoginAtIsNotNull(Long userId);

    boolean existsByUserIdAndIssuerAndLastLoginAtIsNotNull(Long userId, String issuer);

    long countByUserIdAndIssuer(Long userId, String issuer);

    @Query("select max(i.lastLoginAt) from UserIdentity i where i.user.id = :userId and i.issuer = :issuer")
    Instant findLatestLoginAt(@Param("userId") Long userId, @Param("issuer") String issuer);

    @Query("select distinct i.user.id from UserIdentity i where i.user.id in :ids")
    Set<Long> findUserIdsWithIdentity(Collection<Long> ids);

    @Modifying
    @Transactional
    int deleteByUserId(Long userId);
}
