package com.picsou.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "requisition")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Requisition extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id", nullable = false)
    private FamilyMember member;

    /** Enable Banking session ID */
    @Column(name = "requisition_id", nullable = false, unique = true, length = 100)
    private String requisitionId;

    /** Composite "name::country::psuType" — see EnableBankingBankConnector.parseInstitutionId. */
    @Column(name = "institution_id", nullable = false, length = 255)
    private String institutionId;

    @Column(name = "institution_name", length = 200)
    private String institutionName;

    @Column(name = "logo_url", columnDefinition = "TEXT")
    private String logoUrl;

    /** Set the first time a logo backfill lookup is attempted, so a permanent miss isn't retried on every sync. */
    @Column(name = "logo_backfill_attempted_at")
    private Instant logoBackfillAttemptedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "requisition_status")
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.NAMED_ENUM)
    @Builder.Default
    private RequisitionStatus status = RequisitionStatus.CREATED;

    @Column(name = "auth_link", columnDefinition = "TEXT")
    private String authLink;

    /**
     * Random nonce sent as OAuth {@code state} at initiation; Enable Banking
     * echoes it on the redirect, letting the callback resolve this exact
     * requisition. Cleared immediately after a successful code exchange, but
     * retained when the exchange fails so the callback can be retried.
     */
    @JsonIgnore
    @Column(name = "oauth_state", length = 64)
    private String oauthState;

    /** When this connection last successfully synced */
    @Column(name = "last_synced_at")
    private Instant lastSyncedAt;
}
