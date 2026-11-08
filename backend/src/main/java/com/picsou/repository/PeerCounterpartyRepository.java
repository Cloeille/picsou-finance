package com.picsou.repository;

import com.picsou.model.PeerCounterparty;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PeerCounterpartyRepository extends JpaRepository<PeerCounterparty, Long> {

    /** Case-insensitive so the same person is never split into several histories. */
    Optional<PeerCounterparty> findByMemberIdAndNameIgnoreCase(Long memberId, String name);

    List<PeerCounterparty> findAllByMemberIdOrderByNameAsc(Long memberId);
}
