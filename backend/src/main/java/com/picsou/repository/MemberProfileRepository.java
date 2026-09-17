package com.picsou.repository;

import com.picsou.model.MemberProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface MemberProfileRepository extends JpaRepository<MemberProfile, Long> {

    /** Empty means the member has never stated anything — not that they have no profile. */
    Optional<MemberProfile> findByMemberId(Long memberId);
}
