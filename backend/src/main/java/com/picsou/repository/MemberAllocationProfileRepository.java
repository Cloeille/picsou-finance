package com.picsou.repository;

import com.picsou.model.MemberAllocationProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface MemberAllocationProfileRepository extends JpaRepository<MemberAllocationProfile, Long> {

    Optional<MemberAllocationProfile> findByMemberId(Long memberId);
}
