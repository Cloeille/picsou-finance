package com.picsou.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;

/** One ex-date of a {@link DividendSource}. {@code amountEur} is the euro figure at refresh time. */
@Entity
@Table(
    name = "dividend_event",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_dividend_event_source_ex_date",
        columnNames = {"source_id", "ex_date"}
    )
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DividendEvent extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_id", nullable = false)
    private DividendSource source;

    @Column(name = "ex_date", nullable = false)
    private LocalDate exDate;

    /** Cash per share in the source currency, not euros. */
    @Column(nullable = false, precision = 20, scale = 8)
    private BigDecimal amount;

    /**
     * {@code amount} times the FX rate at refresh time. The forecast multiplies this and never
     * calls Yahoo, so a rate limit on a Sunday cannot blank a budget card on Monday.
     */
    @Column(name = "amount_eur", nullable = false, precision = 20, scale = 8)
    private BigDecimal amountEur;

    /** Cash credit date when the source published one. Null when only the ex-date is known. */
    @Column(name = "payment_date")
    private LocalDate paymentDate;

    /**
     * True when the row is a declaration: a future ex-date, from Nasdaq or from Yahoo.
     * History inferred from past ex-dates stays false.
     */
    @Column(nullable = false)
    @Builder.Default
    private boolean declared = false;
}
