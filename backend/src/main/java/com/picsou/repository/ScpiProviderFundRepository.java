package com.picsou.repository;

import com.picsou.model.ScpiProvider;
import com.picsou.model.ScpiProviderFund;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ScpiProviderFundRepository extends JpaRepository<ScpiProviderFund, Long> {

    Optional<ScpiProviderFund> findByMemberIdAndProviderAndFundCode(
        Long memberId, ScpiProvider provider, String fundCode);

    List<ScpiProviderFund> findByMemberIdAndProvider(Long memberId, ScpiProvider provider);

    List<ScpiProviderFund> findByMemberIdOrderByProviderAscLabelAsc(Long memberId);
}
