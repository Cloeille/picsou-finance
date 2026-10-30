package com.picsou.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

/**
 * A monthly spending cap for one {@link Category}, evaluated per payday budget cycle
 * (see {@code BudgetCycle}). There is at most one budget per category — the actual spent
 * amount is computed on read from the member's transactions, never stored.
 */
@Entity
@Table(name = "budget")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Budget extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id", nullable = false)
    private FamilyMember member;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id", nullable = false, unique = true)
    private Category category;

    @Column(name = "monthly_limit", nullable = false, precision = 20, scale = 2)
    private BigDecimal monthlyLimit;
}
