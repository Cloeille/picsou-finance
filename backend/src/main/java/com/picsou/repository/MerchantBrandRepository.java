package com.picsou.repository;

import com.picsou.model.MerchantBrand;
import org.springframework.data.jpa.repository.JpaRepository;

/** Read-only access to the global, seeded merchant knowledge base (brands). */
public interface MerchantBrandRepository extends JpaRepository<MerchantBrand, Long> {
}
