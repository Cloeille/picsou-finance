package com.picsou.repository;

import com.picsou.model.MerchantAlias;
import org.springframework.data.jpa.repository.JpaRepository;

/** Read-only access to the global, seeded merchant knowledge base (aliases). */
public interface MerchantAliasRepository extends JpaRepository<MerchantAlias, Long> {
}
