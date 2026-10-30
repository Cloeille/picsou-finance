package com.picsou.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "revolut_session")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RevolutSession extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id", nullable = false)
    private FamilyMember member;

    /**
     * Phone + passcode, serialized as JSON ({@code {"phone":...,"passcode":...}}) and encrypted
     * at rest via {@code CryptoEncryption} -- present only because the member opted in via
     * {@link #rememberCredentials}. Never stored in plain text, mirroring
     * TradeRepublicSession/BoursoSession. A row only exists for a member who chose to remember;
     * see {@code RevolutSyncService}.
     */
    @Column(name = "credentials_enc", length = 2000)
    private String credentialsEnc;

    /** Whether the member opted to have credentials remembered for unattended daily resync. */
    @Column(name = "remember_credentials", nullable = false)
    private boolean rememberCredentials;

    /** Set after every successful sync (manual or scheduled); informational only. */
    @Column(name = "last_synced_at")
    private Instant lastSyncedAt;
}
