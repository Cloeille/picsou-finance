package com.picsou.repository;

import com.picsou.model.IbkrConnection;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface IbkrConnectionRepository extends JpaRepository<IbkrConnection, Long> {

    // memberId-scoped query — every access is scoped by member, like the other connectors.
    Optional<IbkrConnection> findByMemberId(Long memberId);
}
