package com.picsou.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A spending/income category, scoped to a family member. The default set is seeded
 * lazily by {@code CategoryService} the first time a member's categories are read,
 * so existing and future members both get them without a SQL backfill.
 */
@Entity
@Table(name = "category")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Category extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id", nullable = false)
    private FamilyMember member;

    /**
     * Optional parent, forming a strict <em>one-level</em> tree: a parent never itself has a
     * parent (enforced in {@code CategoryService}). Sub-categories inherit their parent's
     * {@link CategoryKind}; spending drills and budget envelopes roll a parent up over its whole
     * subtree. The {@code parent_id} column ({@code ON DELETE SET NULL}) and its index ship in
     * {@code V36}; only this mapping was missing.
     */
    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private Category parent;

    @Column(nullable = false, length = 100)
    private String name;

    /**
     * Stable, member-agnostic key ({@code "courses"}, {@code "transport"}, …) that the
     * global merchant knowledge base targets to resolve a brand to <em>this</em> member's
     * category. Only the seeded default set carries one; user-created categories leave it
     * null. Unique per member (partial index, see {@code V36}). Survives renames.
     */
    @Column(length = 60)
    private String slug;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false, columnDefinition = "category_kind")
    private CategoryKind kind;

    @Column(nullable = false, length = 7)
    @Builder.Default
    private String color = "#6366f1";

    @Column(length = 50)
    private String icon;

    @Column(name = "is_default", nullable = false)
    @Builder.Default
    private boolean isDefault = false;

    @Column(nullable = false)
    @Builder.Default
    private boolean archived = false;

    @Column(name = "sort_order", nullable = false)
    @Builder.Default
    private int sortOrder = 0;
}
