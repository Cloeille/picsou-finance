package com.picsou.service.budget;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Turns the raw, noisy bank fields ({@code counterparty} / {@code description}) into a
 * clean, human-readable merchant label — the single piece of intelligence behind both
 * "transactions finally have nice names" and brand matching.
 *
 * <p>Pure and stateless on purpose: no Spring, no I/O, fully unit-testable. Bank feeds
 * (Enable Banking / PSD2) give us strings like {@code "PAYPAL *SPOTIFY 4357"},
 * {@code "CB CARREFOUR MARKET PARIS 12 01/02"} or {@code "SUMUP *BOULANGERIE DUPONT"};
 * we strip the payment-processor wrapper, the card/store/reference digits, dates and the
 * leading transaction-type noise, then title-case what remains.
 *
 * <p>Two outputs matter:
 * <ul>
 *   <li>{@link #normalize} — the display label (best-effort clean name).</li>
 *   <li>{@link #matchKey} — a lower-cased, accent-stripped key used to look the label up
 *       in the {@link MerchantKnowledgeBase}. Matching never depends on casing or accents.</li>
 * </ul>
 */
public final class MerchantNormalizer {

    private MerchantNormalizer() {}

    /** Leading transaction-type noise common on French statements. */
    private static final Pattern LEADING_NOISE = Pattern.compile(
        "^(?:achat\\s+)?(?:cb|carte(?:\\s+bancaire)?|paiement(?:\\s+(?:cb|par\\s+carte))?|"
            + "facture\\s+carte|vir(?:ement)?(?:\\s+(?:sepa|instantane|recu|emis))?|"
            + "prlv(?:\\s+sepa)?|prelevement(?:\\s+sepa)?|retrait(?:\\s+dab)?)\\b[ .:*-]*",
        Pattern.CASE_INSENSITIVE);

    /** "carte 1234", "cb no 5678", "card 9012". */
    private static final Pattern CARD_REF = Pattern.compile(
        "\\b(?:carte|cb|card)\\s*n?o?\\.?\\s*\\d{2,}\\b", Pattern.CASE_INSENSITIVE);

    /** Date fragments: 01/02, 01.02.23, 2023-01-02, 12-04 … */
    private static final Pattern DATE_FRAGMENT = Pattern.compile(
        "\\b\\d{1,4}[./-]\\d{1,2}(?:[./-]\\d{1,4})?\\b");

    /** Runs of 3+ digits (card suffixes, store ids, reference numbers). */
    private static final Pattern LONG_DIGITS = Pattern.compile("\\b\\d{3,}\\b");

    private static final Pattern PUNCT = Pattern.compile("[*#/\\\\|]+");
    /** Apostrophes are removed (not spaced) so {@code MCDONALD'S → mcdonalds}, {@code O'TACOS → otacos}. */
    private static final Pattern APOSTROPHE = Pattern.compile("['’`]");
    private static final Pattern COMBINING_MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern MULTISPACE = Pattern.compile("\\s+");

    /**
     * Clean, display-ready merchant label. Returns an empty string only when both inputs
     * are blank. Falls back to the trimmed original if stripping removed everything.
     */
    public static String normalize(String counterparty, String description) {
        String base = firstNonBlank(counterparty, description);
        if (base.isEmpty()) {
            return "";
        }
        String s = stripProcessorPrefix(base.trim());
        s = APOSTROPHE.matcher(s).replaceAll("");
        s = LEADING_NOISE.matcher(s).replaceAll("");
        s = CARD_REF.matcher(s).replaceAll(" ");
        s = DATE_FRAGMENT.matcher(s).replaceAll(" ");
        s = LONG_DIGITS.matcher(s).replaceAll(" ");
        s = PUNCT.matcher(s).replaceAll(" ");
        s = MULTISPACE.matcher(s).replaceAll(" ").trim();
        if (s.isEmpty()) {
            s = MULTISPACE.matcher(base.trim()).replaceAll(" ");
        }
        return titleCase(s);
    }

    /** Lower-cased, accent-stripped, whitespace-collapsed key for KB lookups. */
    public static String matchKey(String label) {
        if (label == null || label.isEmpty()) {
            return "";
        }
        String lower = APOSTROPHE.matcher(label.toLowerCase(Locale.ROOT)).replaceAll("");
        String stripped = COMBINING_MARKS.matcher(
            Normalizer.normalize(lower, Normalizer.Form.NFD)).replaceAll("");
        return MULTISPACE.matcher(stripped).replaceAll(" ").trim();
    }

    /**
     * Payment processors wrap the merchant as {@code "<PROCESSOR> *<MERCHANT>"}. When a
     * '*' is present we keep only what follows the last one — that is the real merchant.
     */
    private static String stripProcessorPrefix(String s) {
        int star = s.lastIndexOf('*');
        if (star >= 0 && star < s.length() - 1) {
            return s.substring(star + 1).trim();
        }
        return s;
    }

    /** Capitalize each word's first letter, lower-casing the rest. */
    private static String titleCase(String s) {
        String[] words = s.toLowerCase(Locale.ROOT).split(" ");
        StringBuilder sb = new StringBuilder(s.length());
        for (String w : words) {
            if (w.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(Character.toUpperCase(w.charAt(0)));
            if (w.length() > 1) {
                sb.append(w.substring(1));
            }
        }
        return sb.toString();
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        if (b != null && !b.isBlank()) {
            return b;
        }
        return "";
    }
}
