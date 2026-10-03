package com.picsou.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Optional;
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
 * it find nothing, which is a missing mark; it cannot make it find the wrong company.
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

    static Optional<LogoUrls> logoUrls(String html, String symbol) {
        if (html == null || symbol == null || symbol.isBlank()) return Optional.empty();
        Matcher m = JSON_SCRIPT.matcher(html);
        while (m.find()) {
            JsonNode root = parse(m.group(1));
            if (root == null) continue;
            Optional<LogoUrls> found = search(root, symbol);
            if (found.isEmpty() && root.path("body").isTextual()) {
                JsonNode body = parse(root.path("body").asText());
                if (body != null) found = search(body, symbol);
            }
            if (found.isPresent()) return found;
        }
        return Optional.empty();
    }

    private static Optional<LogoUrls> search(JsonNode root, String symbol) {
        Deque<JsonNode> stack = new ArrayDeque<>();
        stack.push(root);
        while (!stack.isEmpty()) {
            JsonNode node = stack.pop();
            if (node.isObject()) {
                if (symbol.equalsIgnoreCase(node.path("symbol").asText(null))) {
                    URI light = imageUri(node.path("logoUrl").asText(null));
                    if (light != null) {
                        return Optional.of(new LogoUrls(light, imageUri(node.path("logoUrlDarkMode").asText(null))));
                    }
                }
                node.elements().forEachRemaining(stack::push);
            } else if (node.isArray()) {
                node.elements().forEachRemaining(stack::push);
            }
        }
        return Optional.empty();
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
