package com.picsou.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One SCPI vehicle held in full ownership. The share count can be fractional: a scheduled
 * purchase or a reinvested dividend rarely lands on a whole share.
 */
@Entity
@Table(name = "scpi_position")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ScpiPosition extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false, unique = true)
    private Account account;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id", nullable = false)
    private FamilyMember member;

    @Column(length = 12)
    private String isin;

    @Column(name = "management_company", length = 100)
    private String managementCompany;

    @Column(name = "share_count", nullable = false, precision = 20, scale = 8)
    private BigDecimal shareCount;

    /** What a new share costs. Shown beside the balance, never used as the balance. */
    @Column(name = "subscription_price_eur", precision = 20, scale = 8)
    private BigDecimal subscriptionPriceEur;

    /** What the fund would pay to buy the share back. This, times the count, is the balance. */
    @Column(name = "withdrawal_price_eur", precision = 20, scale = 8)
    private BigDecimal withdrawalPriceEur;

    @Enumerated(EnumType.STRING)
    @Column(name = "dividend_policy", nullable = false, length = 20)
    @Builder.Default
    private DividendPolicy dividendPolicy = DividendPolicy.CASH;

    @Column(name = "jouissance_date")
    private LocalDate jouissanceDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "valuation_status", nullable = false, length = 30)
    @Builder.Default
    private ScpiValuationStatus valuationStatus = ScpiValuationStatus.PRICE_INCOMPLETE;
}
