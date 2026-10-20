package com.picsou.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * What Yahoo last told us a ticker pays, cached so a forecast never has to ask the network.
 *
 * <p>Global rather than member-scoped: an ex-date is not private data. The read path reads only
 * this table; {@code SchedulerService} refreshes it weekly, and a ticker nobody has warmed yet
 * simply does not appear in the forecast rather than blocking a page render on a scrape.
 */
@Entity
@Table(
    name = "dividend_source",
    uniqueConstraints = @UniqueConstraint(name = "uk_dividend_source_ticker", columnNames = "ticker")
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DividendSource extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 30)
    private String ticker;

    /** ISO-ish code Yahoo reported, including {@code GBp}. Null until the first successful read. */
    @Column(length = 10)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @Builder.Default
    private DividendSourceStatus status = DividendSourceStatus.NEVER_FETCHED;

    /**
     * When we last got an answer, empty or not. Drives the 6-day refresh, and does not move
     * on a failure — that is {@link #lastAttemptAt}'s job.
     */
    @Column(name = "refreshed_at")
    private Instant refreshedAt;

    /** When we last tried, successful or not. A failure is retried from this, not from refreshedAt. */
    @Column(name = "last_attempt_at")
    private Instant lastAttemptAt;

    /** Why the last attempt failed, truncated. Diagnostic only — never shown to a member. */
    @Column(name = "last_error", length = 200)
    private String lastError;

    @OneToMany(mappedBy = "source", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    private List<DividendEvent> events = new ArrayList<>();
}
