package com.picsou.repository;

import com.picsou.model.Category;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CategoryRepository extends JpaRepository<Category, Long> {

    List<Category> findAllByMemberIdOrderBySortOrderAscIdAsc(Long memberId);

    List<Category> findAllByMemberIdAndArchivedFalseOrderBySortOrderAscIdAsc(Long memberId);

    Optional<Category> findByIdAndMemberId(Long id, Long memberId);

    boolean existsByMemberId(Long memberId);

    /** Direct children of a parent (member-scoped) — drives cascade archive, drill and rollups. */
    List<Category> findAllByMemberIdAndParentIdOrderBySortOrderAscIdAsc(Long memberId, Long parentId);

    /** Whether a category has any sub-category — i.e. it is a parent. */
    boolean existsByMemberIdAndParentId(Long memberId, Long parentId);
}
