package com.picsou.repository;

import com.picsou.model.FamilyMember;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface FamilyMemberRepository extends JpaRepository<FamilyMember, Long> {
    List<FamilyMember> findAllByOrderByCreatedAtAsc();
    List<FamilyMember> findByManagedTrue();
    List<FamilyMember> findByManagedFalse();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select f from FamilyMember f where f.id = :memberId")
    java.util.Optional<FamilyMember> findByIdForUpdate(@Param("memberId") Long memberId);
}
