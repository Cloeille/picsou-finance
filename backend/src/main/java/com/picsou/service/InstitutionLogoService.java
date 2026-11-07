package com.picsou.service;

import com.picsou.model.Account;
import com.picsou.port.MerchantLogoPort;
import com.picsou.port.MerchantLogoPort.LogoImage;
import com.picsou.repository.AccountRepository;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * The logo of the bank behind an aggregator account, fetched through {@link MerchantLogoPort} and
 * cached in memory by domain, so two Chase accounts cost one request.
 *
 * <p>The domain comes from the bridge, and the bytes come from a site that domain controls (the
 * icon service re-serves what the site publishes), so neither is trusted:
 * <ul>
 *   <li>the domain was reduced to a bare public host when the sync stored it, and the port only
 *       sends it as a path segment to its own fixed host, so it cannot steer the request;</li>
 *   <li>the bytes are served only if they <em>are</em> a small raster image, and the type sent to
 *       the browser is derived from those bytes, never from the upstream header. A hostile site
 *       can publish an SVG or an HTML page as its "icon"; served from this origin that would run
 *       script when the member opens the URL, so those are refused;</li>
 *   <li>the cache is bounded in entries and in entry size, since the domains are not ours to
 *       choose.</li>
 * </ul>
 * The per-member opt-in lives in the controller, as it does for merchant logos.
 */
@Service
public class InstitutionLogoService {

    private static final long HIT_TTL_SECONDS = 86_400;
    private static final long MISS_TTL_SECONDS = 3_600;
    /** Real favicons are a few KB; anything bigger is not one. */
    static final int MAX_LOGO_BYTES = 128 * 1024;
    private static final int MAX_CACHE_ENTRIES = 256;

    private final AccountRepository accountRepository;
    private final MerchantLogoPort logoPort;
    private final ConcurrentMap<String, Cached> cache = new ConcurrentHashMap<>();

    private final Clock clock;

    public InstitutionLogoService(AccountRepository accountRepository, MerchantLogoPort logoPort, Clock clock) {
        this.accountRepository = accountRepository;
        this.logoPort = logoPort;
        this.clock = clock;
    }

    /** Empty when the account is not the member's, has no institution domain, or the fetch failed. */
    public Optional<LogoImage> logoFor(Long accountId, Long memberId) {
        Optional<Account> account = accountRepository.findByIdAndMemberId(accountId, memberId);
        if (account.isEmpty()) return Optional.empty();
        String domain = account.get().getInstitutionDomain();
        if (domain == null || domain.isBlank()) return Optional.empty();

        Instant now = clock.instant();
        Cached cached = cache.get(domain);
        if (cached != null && now.isBefore(cached.expiresAt())) return cached.logo();

        if (cache.size() >= MAX_CACHE_ENTRIES) {
            cache.values().removeIf(entry -> !now.isBefore(entry.expiresAt()));
            if (cache.size() >= MAX_CACHE_ENTRIES) { // full of live entries: serve, do not store
                return logoPort.fetch(domain).flatMap(InstitutionLogoService::vetted);
            }
        }
        // compute() holds the key while it runs, so a page of N cards for one bank costs one
        // upstream request (bounded by the port's timeout), not N.
        return cache.compute(domain, (key, current) -> {
            if (current != null && now.isBefore(current.expiresAt())) return current;
            Optional<LogoImage> fetched = logoPort.fetch(key).flatMap(InstitutionLogoService::vetted);
            return new Cached(fetched, now.plusSeconds(fetched.isPresent() ? HIT_TTL_SECONDS : MISS_TTL_SECONDS));
        }).logo();
    }

    /** The image re-labelled by what its bytes are, or empty if it is not a small raster image. */
    static Optional<LogoImage> vetted(LogoImage image) {
        byte[] b = image.bytes();
        if (b == null || b.length < 12 || b.length > MAX_LOGO_BYTES) return Optional.empty();
        String type = sniff(b);
        return type == null ? Optional.empty() : Optional.of(new LogoImage(b, type));
    }

    private static String sniff(byte[] b) {
        if ((b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') return "image/png";
        if ((b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) return "image/jpeg";
        if (b[0] == 'G' && b[1] == 'I' && b[2] == 'F' && b[3] == '8') return "image/gif";
        if (b[0] == 0 && b[1] == 0 && b[2] == 1 && b[3] == 0) return "image/x-icon";
        if (b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
            && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') return "image/webp";
        return null;
    }

    private record Cached(Optional<LogoImage> logo, Instant expiresAt) {}
}
