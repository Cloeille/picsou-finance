package com.picsou.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

/**
 * One line of a recurring investment plan's monthly amount.
 *
 * <p>"400 € a month into the PEA" says when and where; these rows say into what — 200 € of CW8,
 * 200 € of ESE. A plan may carry none, and most do: an empty set means the member has not
 * detailed the split, never that the money goes nowhere.
 *
 * <p>Keyed on the ticker rather than on an {@link AccountHolding} id, for the reason
 * {@code holding_classification} is: the sync paths delete and rebuild holding rows, so a
 * foreign key there would evaporate on the first transient gap and take the split with it.
 * {@code GoalService} is what enforces that the ticker is one the funded account actually holds.
 */
@Entity
@Table(
    name = "goal_allocation",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_goal_allocation_goal_ticker",
        columnNames = {"goal_id", "ticker"}
    )
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GoalAllocation extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "goal_id", nullable = false)
    private Goal goal;

    @Column(nullable = false, length = 30)
    private String ticker;

    @Column(name = "monthly_amount", nullable = false, precision = 20, scale = 2)
    private BigDecimal monthlyAmount;
}
