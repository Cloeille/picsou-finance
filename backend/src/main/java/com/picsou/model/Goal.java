package com.picsou.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "goal")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Goal extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id", nullable = false)
    private FamilyMember member;

    @Column(nullable = false, length = 200)
    private String name;

    /**
     * Which shape this goal has. Defaulted rather than required so every row written before V85 —
     * and every client that has not learned about the field — keeps meaning what it meant.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    @Builder.Default
    private GoalType type = GoalType.SAVINGS_TARGET;

    /** Required for {@code SAVINGS_TARGET}, always null for a recurring investment. */
    @Column(name = "target_amount", precision = 20, scale = 2)
    private BigDecimal targetAmount;

    /** Required for {@code SAVINGS_TARGET}, always null for a recurring investment. */
    private LocalDate deadline;

    /** Required for {@code RECURRING_INVESTMENT}: what goes in every month. */
    @Column(name = "monthly_amount", precision = 20, scale = 2)
    private BigDecimal monthlyAmount;

    /**
     * The member's own return assumption for this plan, in percent.
     *
     * <p>Used by {@code ProjectionService}: the plan gets its own pot compounding at this rate,
     * falling back to its tier's when null. It was ignored at first, on the grounds that folding
     * a per-goal rate into a line labelled "5 %" would make the label false — but the label was
     * already false, since a Livret A the member had typed 1.7 % into was being compounded at the
     * equity rate. The scenarios became spreads on risky assets instead, which lets a stated rate
     * and an honest label coexist. See docs/features/goal-recurring-investment.md.
     */
    @Column(name = "expected_return", precision = 6, scale = 3)
    private BigDecimal expectedReturn;

    /** When the contributions start; null means "already running". */
    @Column(name = "start_date")
    private LocalDate startDate;

    /** When they stop; null means open-ended. */
    @Column(name = "end_date")
    private LocalDate endDate;

    /** Optional backfill start ("YYYY-MM"). When earlier than createdAt, the calendar extends back to it. */
    @Column(name = "history_start_month", length = 7)
    private String historyStartMonth;

    @JsonIgnore
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
        name = "goal_account",
        joinColumns = @JoinColumn(name = "goal_id"),
        inverseJoinColumns = @JoinColumn(name = "account_id")
    )
    @Builder.Default
    private List<Account> accounts = new ArrayList<>();

    /**
     * Where the monthly amount goes, line by line. Empty for every savings target and for a plan
     * whose owner has not detailed the split.
     *
     * <p>Owned by the goal — {@code orphanRemoval} is what lets an edit drop a line. That makes
     * this list's <em>identity</em> load-bearing: replacing it wholesale
     * ({@code setAllocations(new ArrayList<>(…))}) makes Hibernate throw "a collection with
     * cascade=all-delete-orphan was no longer referenced". Mutate it in place instead —
     * {@code GoalService.replaceAllocations} is the one place that does.
     */
    @JsonIgnore
    @OneToMany(mappedBy = "goal", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    private List<GoalAllocation> allocations = new ArrayList<>();
}
