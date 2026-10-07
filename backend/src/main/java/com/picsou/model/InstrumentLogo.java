package com.picsou.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * A listed instrument's mark, stored as bytes and served by Picsou itself.
 *
 * <p>Global rather than member-scoped, like {@link SecurityProfile}: a company's logo is not
 * private data, and one row serves every account and member holding the ticker. Rows are never
 * deleted when a position goes away (issue #162).
 *
 * <p>Load the entity only to serve one image. Listing which tickers have a mark goes through the
 * repository's projection, so a holdings page never pulls image bytes out of the database.
 */
@Entity
@Table(
    name = "instrument_logo",
    uniqueConstraints = @UniqueConstraint(name = "uk_instrument_logo_ticker", columnNames = "ticker")
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InstrumentLogo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Upper-cased holding ticker. */
    @Column(nullable = false, length = 30)
    private String ticker;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private InstrumentLogoStatus status;

    /** The mark drawn for a light background. Present exactly when {@link #status} is STORED. */
    @Column(name = "image")
    private byte[] image;

    @Column(name = "content_type", length = 32)
    private String contentType;

    /** The mark drawn for a dark background, when the source has a distinct one. */
    @Column(name = "image_dark")
    private byte[] imageDark;

    @Column(name = "content_type_dark", length = 32)
    private String contentTypeDark;

    @Column(name = "attempted_at", nullable = false)
    private Instant attemptedAt;

    /** When the stored bytes were downloaded. Also the cache-busting version in the served URL. */
    @Column(name = "fetched_at")
    private Instant fetchedAt;
}
