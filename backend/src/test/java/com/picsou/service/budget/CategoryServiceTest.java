package com.picsou.service.budget;

import com.picsou.dto.CategoryRequest;
import com.picsou.dto.CategoryResponse;
import com.picsou.exception.ResourceNotFoundException;
import com.picsou.model.Category;
import com.picsou.model.CategoryKind;
import com.picsou.repository.CategoryRepository;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.TransactionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * The M4 sub-category invariants — the strict one-level tree and the archive cascade.
 * Pure Mockito (no DB): the {@code parent} back-reference is a plain field on the built
 * entity, so {@code resolveParent}'s checks run against real objects, not Hibernate proxies.
 */
@ExtendWith(MockitoExtension.class)
class CategoryServiceTest {

    @Mock CategoryRepository categoryRepository;
    @Mock TransactionRepository transactionRepository;
    @Mock FamilyMemberRepository familyMemberRepository;

    @InjectMocks CategoryService service;

    private static final Long MEMBER = 10L;

    private static CategoryRequest req(String name, CategoryKind kind, Long parentId) {
        // Explicit sortOrder keeps create off the nextSortOrder() path (not under test here).
        return new CategoryRequest(name, kind, "#22c55e", "tag", 0, parentId);
    }

    private static Category cat(long id, CategoryKind kind) {
        return Category.builder().id(id).kind(kind).name("Cat" + id).color("#22c55e").build();
    }

    private static Category cat(long id, CategoryKind kind, Category parent) {
        return Category.builder().id(id).kind(kind).name("Cat" + id).color("#22c55e").parent(parent).build();
    }

    // ─── create: parent resolution ───────────────────────────────────────────

    @Test
    void create_attachesParent_whenValid() {
        Category parent = cat(7L, CategoryKind.EXPENSE); // root, same kind
        when(categoryRepository.findByIdAndMemberId(7L, MEMBER)).thenReturn(Optional.of(parent));
        when(categoryRepository.save(any(Category.class))).thenAnswer(inv -> inv.getArgument(0));

        CategoryResponse resp = service.create(req("Courses", CategoryKind.EXPENSE, 7L), MEMBER);

        assertThat(resp.parentId()).isEqualTo(7L);
    }

    @Test
    void create_isTopLevel_whenParentIdNull() {
        when(categoryRepository.save(any(Category.class))).thenAnswer(inv -> inv.getArgument(0));

        CategoryResponse resp = service.create(req("Courses", CategoryKind.EXPENSE, null), MEMBER);

        assertThat(resp.parentId()).isNull();
    }

    @Test
    void create_rejects_whenParentKindDiffers() {
        Category parent = cat(7L, CategoryKind.INCOME); // root but wrong kind
        when(categoryRepository.findByIdAndMemberId(7L, MEMBER)).thenReturn(Optional.of(parent));

        assertThatThrownBy(() -> service.create(req("Courses", CategoryKind.EXPENSE, 7L), MEMBER))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("share its parent's kind");
    }

    @Test
    void create_rejects_whenParentIsItselfAChild() {
        Category grandparent = cat(1L, CategoryKind.EXPENSE);
        Category parent = cat(7L, CategoryKind.EXPENSE, grandparent); // already nested one level
        when(categoryRepository.findByIdAndMemberId(7L, MEMBER)).thenReturn(Optional.of(parent));

        assertThatThrownBy(() -> service.create(req("Courses", CategoryKind.EXPENSE, 7L), MEMBER))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("single level of nesting");
    }

    @Test
    void create_rejects_whenParentMissing() {
        when(categoryRepository.findByIdAndMemberId(99L, MEMBER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(req("Courses", CategoryKind.EXPENSE, 99L), MEMBER))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    // ─── update: re-parenting & children guards ──────────────────────────────

    @Test
    void update_reparents_whenValid() {
        Category leaf = cat(5L, CategoryKind.EXPENSE);
        Category parent = cat(7L, CategoryKind.EXPENSE);
        when(categoryRepository.findByIdAndMemberId(5L, MEMBER)).thenReturn(Optional.of(leaf));
        when(categoryRepository.existsByMemberIdAndParentId(MEMBER, 5L)).thenReturn(false);
        when(categoryRepository.findByIdAndMemberId(7L, MEMBER)).thenReturn(Optional.of(parent));
        when(categoryRepository.save(any(Category.class))).thenAnswer(inv -> inv.getArgument(0));

        CategoryResponse resp = service.update(5L, req("Courses", CategoryKind.EXPENSE, 7L), MEMBER);

        assertThat(resp.parentId()).isEqualTo(7L);
    }

    @Test
    void update_rejects_whenCategoryWouldBecomeItsOwnParent() {
        Category leaf = cat(5L, CategoryKind.EXPENSE);
        when(categoryRepository.findByIdAndMemberId(5L, MEMBER)).thenReturn(Optional.of(leaf));
        when(categoryRepository.existsByMemberIdAndParentId(MEMBER, 5L)).thenReturn(false);

        assertThatThrownBy(() -> service.update(5L, req("Courses", CategoryKind.EXPENSE, 5L), MEMBER))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("its own parent");
    }

    @Test
    void update_rejects_whenParentWithChildrenBecomesChild() {
        Category parent = cat(7L, CategoryKind.EXPENSE);
        when(categoryRepository.findByIdAndMemberId(7L, MEMBER)).thenReturn(Optional.of(parent));
        when(categoryRepository.existsByMemberIdAndParentId(MEMBER, 7L)).thenReturn(true); // has children

        assertThatThrownBy(() -> service.update(7L, req("Maison", CategoryKind.EXPENSE, 1L), MEMBER))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("cannot become a sub-category");
    }

    @Test
    void update_rejects_whenParentWithChildrenChangesKind() {
        Category parent = cat(7L, CategoryKind.EXPENSE);
        when(categoryRepository.findByIdAndMemberId(7L, MEMBER)).thenReturn(Optional.of(parent));
        when(categoryRepository.existsByMemberIdAndParentId(MEMBER, 7L)).thenReturn(true);

        assertThatThrownBy(() -> service.update(7L, req("Maison", CategoryKind.INCOME, null), MEMBER))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("cannot change kind");
    }

    // ─── archive cascade ──────────────────────────────────────────────────────

    @Test
    void archive_cascadesToChildren() {
        Category parent = cat(7L, CategoryKind.EXPENSE);
        Category child1 = cat(5L, CategoryKind.EXPENSE, parent);
        Category child2 = cat(6L, CategoryKind.EXPENSE, parent);
        when(categoryRepository.findByIdAndMemberId(7L, MEMBER)).thenReturn(Optional.of(parent));
        when(categoryRepository.findAllByMemberIdAndParentIdOrderBySortOrderAscIdAsc(MEMBER, 7L))
            .thenReturn(List.of(child1, child2));

        service.archive(7L, MEMBER);

        assertThat(parent.isArchived()).isTrue();
        assertThat(child1.isArchived()).isTrue();
        assertThat(child2.isArchived()).isTrue();
    }

    @Test
    void unarchive_cascadesToChildren() {
        Category parent = cat(7L, CategoryKind.EXPENSE);
        parent.setArchived(true);
        Category child1 = cat(5L, CategoryKind.EXPENSE, parent);
        child1.setArchived(true);
        when(categoryRepository.findByIdAndMemberId(7L, MEMBER)).thenReturn(Optional.of(parent));
        when(categoryRepository.findAllByMemberIdAndParentIdOrderBySortOrderAscIdAsc(MEMBER, 7L))
            .thenReturn(List.of(child1));

        CategoryResponse resp = service.unarchive(7L, MEMBER);

        assertThat(resp.archived()).isFalse();
        assertThat(parent.isArchived()).isFalse();
        assertThat(child1.isArchived()).isFalse();
    }
}
