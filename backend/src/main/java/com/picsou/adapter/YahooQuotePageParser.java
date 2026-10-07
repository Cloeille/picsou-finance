package com.picsou.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the logo URLs Yahoo's quote page carries for the symbol it is about.
 *
 * <p>The page embeds its API responses as {@code <script type="application/json">} blocks, each a
 * JSON envelope whose {@code body} is the response as a JSON string. A quote object in there has
 * {@code symbol}, {@code logoUrl} (a mark for a light background) and {@code logoUrlDarkMode}.
 *
 * <p>The page also lists dozens of <em>other</em> companies (trending tickers, "people also
 * watch"), each with its own logo URL, and the first {@code s.yimg.com/lg/logos/} URL in the
 * page belongs to one of them. So this never scans the page for a logo URL: it parses the JSON
 * and only accepts the object whose {@code symbol} is the one asked for. A layout change can make
 * it find nothing, which it reports as {@link Result.NotQuoted} or {@link Result.RefusedMark},
 * never as a missing mark; it cannot make it find the wrong company.
 */
final class YahooQuotePageParser {

    /** The only host a logo may be downloaded from. Anything else in the page is ignored. */
    static final String IMAGE_HOST = "s.yimg.com";

    private static final ObjectMapper JSON = new ObjectMapper();

    /** The tag boundaries only; what is inside is handed to a JSON parser. */
    private static final Pattern JSON_SCRIPT = Pattern.compile(
        "<script\\b[^>]*\\btype=\"application/json\"[^>]*>(.*?)</script>",
        Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    private YahooQuotePageParser() {}

    record LogoUrls(URI light, URI dark) {}

    /** What the page says about one symbol. */
    sealed interface Result {
        /** The page quotes the symbol and carries a usable mark for it. */
        record Marked(LogoUrls urls) implements Result {}

        /**
         * The page quotes the symbol without a {@code logoUrl}, and carries other companies'
         * marks, so it is the layout this parser reads: Yahoo has none.
         */
        record Unmarked() implements Result {}

        /**
         * The quote has a {@code logoUrl} this parser will not download (another host, not
         * https): a CDN move, not a missing mark.
         */
        record RefusedMark() implements Result {}

        /**
         * No quote object for the symbol, or one on a page carrying no usable mark at all: a
         * layout this parser no longer reads, or a consent or anti-bot page served with a 200.
         * Says nothing about whether a mark exists.
         */
        record NotQuoted() implements Result {}
    }

    static Result read(String html, String symbol) {
        if (html == null || symbol == null || symbol.isBlank()) return new Result.NotQuoted();
        Scan scan = new Scan(symbol);
        Matcher m = JSON_SCRIPT.matcher(html);
        while (m.find()) {
            JsonNode root = parse(m.group(1));
            if (root == null) continue;
            scan.visit(root);
            if (scan.mark == null && root.path("body").isTextual()) scan.visit(parse(root.path("body").asText()));
            if (scan.mark != null) return new Result.Marked(scan.mark);
        }
        return scan.verdict();
    }

    /** What the whole page has said so far about the symbol, and whether it carries any mark. */
    private static final class Scan {
        private final String symbol;
        private LogoUrls mark;
        private boolean markRefused;
        private boolean quotedWithoutMark;
        private boolean pageHasAMark;

        Scan(String symbol) {
            this.symbol = symbol;
        }

        void visit(JsonNode root) {
            if (root == null) return;
            Deque<JsonNode> stack = new ArrayDeque<>();
            stack.push(root);
            while (!stack.isEmpty()) {
                JsonNode node = stack.pop();
                if (node.isObject()) {
                    String raw = node.path("logoUrl").asText(null);
                    URI light = imageUri(raw);
                    if (light != null) pageHasAMark = true;
                    if (symbol.equalsIgnoreCase(node.path("symbol").asText(null))) {
                        if (light != null) {
                            mark = new LogoUrls(light, imageUri(node.path("logoUrlDarkMode").asText(null)));
                            return;
                        }
                        // Only a quote object counts: the page also has other objects keyed by
                        // the symbol (recommendations, news) that never carry a logo.
                        if (raw != null && !raw.isBlank()) markRefused = true;
                        else if (node.has("quoteType")) quotedWithoutMark = true;
                    }
                }
                if (node.isContainerNode()) node.elements().forEachRemaining(stack::push);
            }
        }

        /**
         * A missing mark is only believed on a page that carries other companies' marks, which
         * Yahoo's quote pages always do: without one, the field was renamed or moved and every
         * symbol would look unmarked.
         */
        Result verdict() {
            if (markRefused) return new Result.RefusedMark();
            if (quotedWithoutMark && pageHasAMark) return new Result.Unmarked();
            return new Result.NotQuoted();
        }
    }

    /** An https URL on {@link #IMAGE_HOST}, or null. The page is untrusted input. */
    static URI imageUri(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            URI uri = URI.create(raw.trim());
            if (!"https".equals(uri.getScheme())) return null;
            if (uri.getHost() == null || !IMAGE_HOST.equals(uri.getHost().toLowerCase(Locale.ROOT))) return null;
            if (uri.getUserInfo() != null || (uri.getPort() != -1 && uri.getPort() != 443)) return null;
            return uri;
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static JsonNode parse(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            return JSON.readTree(text);
        } catch (Exception ex) {
            return null;
        }
    }
}
