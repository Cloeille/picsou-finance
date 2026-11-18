package com.picsou.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;

/**
 * One member's display preferences for one account: manual list position and
 * collapsing. Absence of a row means defaults (natural order, shown).
 *
 * <p>Display-only: unlike {@code hidden}, these never exclude the account from
 * any total, budget or chart. Rows may reference any account the member can
 * read (owned or shared) — every viewer arranges their own view.
 */
@Entity
@Table(
    name = "account_display_pref",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_account_display_pref_account_member",
        columnNames = {"account_id", "member_id"}
    )
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AccountDisplayPref extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id", nullable = false)
    private FamilyMember member;

    /** Manual position in the account list; NULL keeps natural order after the positioned rows. */
    @Column(name = "list_position")
    private Integer listPosition;

    /** Collapsed in the account list only; still counted everywhere. */
    @Column(name = "list_collapsed", nullable = false)
    @Builder.Default
    private boolean listCollapsed = false;
}
