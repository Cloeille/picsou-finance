package com.picsou.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

/** One weighted line of an ETF's look-through: a company, a country or a sector. */
@Entity
@Table(
    name = "security_composition_slice",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_security_composition_slice_profile_kind_label",
        columnNames = {"profile_id", "kind", "label"}
    )
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SecurityCompositionSlice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "profile_id", nullable = false)
    private SecurityProfile profile;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private SecuritySliceKind kind;

    /**
     * A stable key for a country or sector, a real company name for a company. The distinction
     * matters to the client: keys get translated, names are rendered verbatim.
     */
    @Column(nullable = false, length = 120)
    private String label;

    @Column(nullable = false, precision = 6, scale = 3)
    private BigDecimal percent;
}
