package com.picsou.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;

/**
 * The person a peer loan is made to. Deliberately not a {@link FamilyMember}: that is a
 * user of this Picsou household, while a borrower is an outside person the member types
 * once. A shared row per member keeps "Marie", "marie" and "Marie " from splitting one
 * history into three, which is the only thing that makes the borrower history useful.
 */
@Entity
@Table(name = "peer_counterparty")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PeerCounterparty extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id", nullable = false)
    private FamilyMember member;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(length = 500)
    private String note;
}
