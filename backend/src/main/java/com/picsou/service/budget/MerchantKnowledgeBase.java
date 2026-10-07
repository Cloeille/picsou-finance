package com.picsou.service.budget;

import com.picsou.model.MerchantAlias;
import com.picsou.model.MerchantBrand;
import com.picsou.repository.MerchantAliasRepository;
import com.picsou.repository.MerchantBrandRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * In-memory, offline brand lookup over the seeded {@code merchant_brand}/{@code merchant_alias}
 * tables. The whole KB is loaded <b>once</b> at startup into an immutable snapshot and matched
 * with zero per-transaction I/O — categorizing a sync batch never touches the database for the KB.
 *
 * <p><b>Thread-safety</b>: the snapshot is published through a single {@code volatile} reference.
 * {@link #reload()} builds a fresh immutable snapshot and swaps the reference atomically, so
 * readers always see a fully-built, consistent KB (a KB-version bump can call {@link #reload()}).
 *
 * <p><b>Matching</b> (see {@link #match}) consumes a key produced by
 * {@link MerchantNormalizer#matchKey} (lower-cased, accent-free, whitespace-collapsed):
 * <ol>
 *   <li>multi-word {@code PHRASE} aliases first, longest pattern first — the most specific wins
 *       ({@code "carrefour market"} beats a bare {@code "carrefour"});</li>
 *   <li>then single {@code WORD} aliases against each token.</li>
 * </ol>
 */
@Component
public class MerchantKnowledgeBase {

    private static final Logger log = LoggerFactory.getLogger(MerchantKnowledgeBase.class);

    private final MerchantBrandRepository brandRepository;
    private final MerchantAliasRepository aliasRepository;

    /** Published immutably; swapped wholesale on {@link #reload()}. */
    private volatile Snapshot snapshot = Snapshot.EMPTY;

    public MerchantKnowledgeBase(MerchantBrandRepository brandRepository,
                                 MerchantAliasRepository aliasRepository) {
        this.brandRepository = brandRepository;
        this.aliasRepository = aliasRepository;
    }

    @PostConstruct
    public void reload() {
        Map<Long, Brand> byId = new HashMap<>();
        for (MerchantBrand b : brandRepository.findAll()) {
            byId.put(b.getId(), new Brand(
                b.getId(), b.getSlug(), b.getDisplayName(), b.getDefaultCategorySlug(),
                b.getColor(), b.getMonogram(), b.getLogoDomain()));
        }

        Map<String, Brand> byWord = new HashMap<>();
        List<PhraseAlias> phrases = new ArrayList<>();
        for (MerchantAlias a : aliasRepository.findAll()) {
            Brand brand = byId.get(a.getBrandId());
            if (brand == null) {
                continue; // orphan alias — defensive, should not happen with FK-backed seed
            }
            if (a.getMatchType() == MerchantAlias.MatchType.PHRASE) {
                phrases.add(new PhraseAlias(a.getPattern(), brand));
            } else {
                byWord.putIfAbsent(a.getPattern(), brand);
            }
        }
        // Longest phrase first so the most specific match wins.
        phrases.sort((x, y) -> Integer.compare(y.pattern().length(), x.pattern().length()));

        this.snapshot = new Snapshot(Map.copyOf(byId), Map.copyOf(byWord), List.copyOf(phrases));
        log.info("Merchant knowledge base loaded: {} brands, {} word aliases, {} phrase aliases",
            byId.size(), byWord.size(), phrases.size());
    }

    /** Resolve a normalized match-key to a brand, or empty if unknown. */
    public Optional<Brand> match(String matchKey) {
        if (matchKey == null || matchKey.isBlank()) {
            return Optional.empty();
        }
        Snapshot s = this.snapshot;
        for (PhraseAlias p : s.phrases()) {
            if (containsWord(matchKey, p.pattern())) {
                return Optional.of(p.brand());
            }
        }
        // Split on whitespace and dots so "e.leclerc" → {e, leclerc} and "netflix.com" → {netflix, com};
        // '&' and '-' stay inside a token (so "h&m" remains matchable as one word alias).
        for (String token : matchKey.split("[\\s.]+")) {
            Brand b = s.byWord().get(token);
            if (b != null) {
                return Optional.of(b);
            }
        }
        return Optional.empty();
    }

    /** Look a brand up by its primary key (used to resolve an already-stamped merchant_brand_id). */
    public Optional<Brand> findById(Long id) {
        return id == null ? Optional.empty() : Optional.ofNullable(this.snapshot.byId().get(id));
    }

    /** Number of brands currently loaded (diagnostics/tests). */
    public int brandCount() {
        return this.snapshot.byId().size();
    }

    /**
     * Word-boundary containment: {@code needle} must appear in {@code haystack} delimited by
     * spaces or string ends, so {@code "paul"} does not match {@code "paula"} and
     * {@code "apple.com/bill"} matches only as a whole phrase.
     */
    private static boolean containsWord(String haystack, String needle) {
        int from = 0;
        int idx;
        while ((idx = haystack.indexOf(needle, from)) >= 0) {
            boolean leftOk = idx == 0 || haystack.charAt(idx - 1) == ' ';
            int end = idx + needle.length();
            boolean rightOk = end == haystack.length() || haystack.charAt(end) == ' ';
            if (leftOk && rightOk) {
                return true;
            }
            from = idx + 1;
        }
        return false;
    }

    /** Immutable view of one brand, decoupled from the JPA entity. */
    public record Brand(Long id, String slug, String displayName, String defaultCategorySlug,
                        String color, String monogram, String logoDomain) {}

    private record PhraseAlias(String pattern, Brand brand) {}

    private record Snapshot(Map<Long, Brand> byId, Map<String, Brand> byWord, List<PhraseAlias> phrases) {
        static final Snapshot EMPTY = new Snapshot(Map.of(), Map.of(), List.of());
    }
}
