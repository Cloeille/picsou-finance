package com.picsou.repository;

import com.picsou.model.LouveOAuthClientRegistration;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * Instance-wide, not member-scoped: a registration belongs to the Picsou origin, not to a member,
 * and holds no member data (see the Louve Invest ADR).
 */
public interface LouveOAuthClientRepository extends JpaRepository<LouveOAuthClientRegistration, Long> {

    Optional<LouveOAuthClientRegistration> findByRedirectUri(String redirectUri);

    /**
     * Compare-and-delete in one statement: removes the registration of {@code redirectUri} only
     * while it still holds {@code clientId}, so a newer registration survives. A bulk DELETE, not a
     * derived delete (SELECT then delete by id), so two instances forgetting the same client both
     * succeed, one with 0 rows, instead of one failing on an optimistic lock.
     */
    @Modifying
    @Query("DELETE FROM LouveOAuthClientRegistration r WHERE r.redirectUri = :redirectUri AND r.clientId = :clientId")
    int deleteRegistration(@Param("redirectUri") String redirectUri, @Param("clientId") String clientId);
}
