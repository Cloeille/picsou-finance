package com.picsou.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.DynamicUpdate;

import java.time.Instant;

/**
 * Per-member Wise connection: the personal API token that reads the member's jars,
 * encrypted at rest and never returned to the client.
 *
 * <p>Updates write only the changed columns. A sync loads the row before calling Wise and saves
 * its status afterwards; a full-row update would put back the token it loaded over one a
 * concurrent connect stored in between.
 */
@Entity
@DynamicUpdate
@Table(name = "wise_connection")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WiseConnection extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id", nullable = false)
    private FamilyMember member;

    /** Personal API token, AES-256-GCM encrypted. */
    @JsonIgnore
    @Column(name = "api_token", nullable = false, columnDefinition = "TEXT")
    private String apiToken;

    @Column(nullable = false, length = 20)
    @Builder.Default
    private String status = "CONNECTED";

    @Column(name = "last_synced_at")
    private Instant lastSyncedAt;
}
