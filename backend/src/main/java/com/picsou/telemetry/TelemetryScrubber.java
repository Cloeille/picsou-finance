package com.picsou.telemetry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Allowlist scrubber for Sentry-protocol events. Pure Java, framework-free: it works on the
 * JSON-ish structure ({@code Map}/{@code List}/{@code String}/{@code Number}/{@code Boolean})
 * so the very same rules apply to an event serialized by the backend SDK and to an event
 * tunneled in from the browser. Mirrors the TypeScript scrubber on the frontend (second layer).
 *
 * <p>Nothing is copied unless it is explicitly allowlisted below: everything not named here
 * ({@code request}, {@code user}, {@code breadcrumbs}, {@code extra}, {@code modules},
 * {@code server_name}, {@code debug_meta}, frame vars / context lines, …) is dropped.
 * Every kept free-text string goes through {@link #scrubText(String)}.
 */
public final class TelemetryScrubber {

    private static final int MAX_INPUT = 4_000;
    private static final int MAX_VALUE = 500;
    private static final int MAX_NAME = 200;
    private static final int MAX_TAG_VALUE = 64;
    private static final int MAX_EXCEPTIONS = 10;
    private static final int MAX_FRAMES = 100;
    private static final int MAX_FINGERPRINT = 10;

    private static final Set<String> LEVELS = Set.of("fatal", "error", "warning", "info", "debug");

    private static final Pattern EVENT_ID = Pattern.compile("^[0-9a-fA-F-]{32,36}$");
    private static final Pattern TIMESTAMP = Pattern.compile("^[0-9T:.+\\-Z ]{10,40}$");
    private static final Pattern PLATFORM = Pattern.compile("^[a-z]{1,20}$");
    private static final Pattern LOGGER = Pattern.compile("^[\\w.$\\-]{1,100}$");
    private static final Pattern RELEASE = Pattern.compile("^[\\w.+@\\-]{1,100}$");
    private static final Pattern ENVIRONMENT = Pattern.compile("^[\\w.\\-]{1,64}$");
    private static final Pattern MECHANISM_TYPE = Pattern.compile("^[\\w.\\-]{1,64}$");
    private static final Pattern SDK_NAME = Pattern.compile("^[\\w./\\-]{1,64}$");
    private static final Pattern VERSION = Pattern.compile("^[\\w.+\\-]{1,32}$");
    private static final Pattern CONTEXT_NAME = Pattern.compile("^[\\w .+\\-]{1,64}$");
    private static final Pattern TAG_KEY = Pattern.compile("^(?:route|event|feature|ab\\.[\\w.\\-]{1,60})$");

    // ---- string rules ------------------------------------------------------------------------
    private static final Pattern URL = Pattern.compile("https?://[^\\s\"'<>()\\[\\]{}]+");
    private static final Pattern ORIGIN = Pattern.compile("^[a-zA-Z][a-zA-Z0-9+.\\-]*://[^/?#]*");
    private static final Pattern JWT = Pattern.compile("eyJ[\\w\\-]+\\.[\\w\\-]+\\.[\\w\\-]*");
    private static final Pattern BEARER = Pattern.compile("(?i)\\bBearer\\s+[\\w\\-.~+/]+=*");
    private static final Pattern SECRET_PAIR = Pattern.compile(
        "(?i)\\b(key|token|password|passwd|secret|api[_-]?key|access[_-]?token|authorization)"
            + "([\"']?\\s*[=:]\\s*[\"']?)[^\\s&;,\"'}]+");
    private static final Pattern EMAIL = Pattern.compile("[\\w.+\\-]+@[\\w\\-]+(?:\\.[\\w\\-]+)+");
    private static final Pattern IBAN = Pattern.compile("\\b[A-Z]{2}\\d{2}(?: ?[A-Z0-9]){10,30}\\b");
    private static final Pattern CARD = Pattern.compile("(?<!\\d)(?:\\d[ -]?){12,18}\\d(?!\\d)");
    private static final Pattern IPV4 = Pattern.compile("(?<![\\d.])\\d{1,3}(?:\\.\\d{1,3}){3}(?![\\d.])");
    private static final String NUM = "\\d+(?:[ \\u00a0\\u202f.,]\\d{3})*(?:[.,]\\d+)?";
    private static final Pattern AMOUNT_SIGN_BEFORE = Pattern.compile("[€$£]\\s?" + NUM);
    private static final Pattern AMOUNT_CODE_AFTER = Pattern.compile(
        "(?i)(?<!\\d)" + NUM + "\\s?(?:€|\\$|£|EUR|USD|GBP|CHF|euros?|dollars?)(?![A-Za-z])");
    private static final Pattern AMOUNT_DECIMALS = Pattern.compile(
        "(?<!\\d)\\d+(?:[ \\u00a0\\u202f.,]\\d{3})*[.,]\\d{2}(?!\\d)");
    private static final Pattern LONG_NUMBER = Pattern.compile("\\d{4,}");

    private static final Pattern UUID_SEGMENT = Pattern.compile(
        "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final Pattern HEX_SEGMENT = Pattern.compile("^[0-9a-fA-F]{12,}$");
    private static final Pattern OPAQUE_SEGMENT = Pattern.compile("^[A-Za-z0-9_=\\-]{20,}$");

    private TelemetryScrubber() {
    }

    // ---- public string API -------------------------------------------------------------------

    /** Scrubs a free-text string. Order matters: URLs first (they carry the most ids/tokens). */
    public static String scrubText(String input) {
        if (input == null) {
            return null;
        }
        String s = input.length() > MAX_INPUT ? input.substring(0, MAX_INPUT) : input;
        s = replaceUrls(s);
        s = JWT.matcher(s).replaceAll("[token]");
        s = BEARER.matcher(s).replaceAll("[token]");
        s = SECRET_PAIR.matcher(s).replaceAll("$1$2[token]");
        s = EMAIL.matcher(s).replaceAll("[email]");
        s = IBAN.matcher(s).replaceAll("[iban]");
        s = CARD.matcher(s).replaceAll("[card]");
        s = IPV4.matcher(s).replaceAll("[ip]");
        s = AMOUNT_SIGN_BEFORE.matcher(s).replaceAll("[amount]");
        s = AMOUNT_CODE_AFTER.matcher(s).replaceAll("[amount]");
        s = AMOUNT_DECIMALS.matcher(s).replaceAll("[amount]");
        s = LONG_NUMBER.matcher(s).replaceAll("[n]");
        return s;
    }

    /**
     * Route template: drops scheme/host, query and fragment, and replaces id-looking path segments
     * with {@code :id}. {@code /accounts/123?x=1} → {@code /accounts/:id}.
     */
    public static String routeTemplate(String path) {
        if (path == null) {
            return null;
        }
        String p = stripQueryAndFragment(path);
        Matcher origin = ORIGIN.matcher(p);
        if (origin.find()) {
            p = p.substring(origin.end());
        }
        return templatePath(p);
    }

    /** Stack-frame filename: path only (no host / query), route-templated, then text-scrubbed. */
    public static String scrubFilename(String filename) {
        if (filename == null) {
            return null;
        }
        return cut(scrubText(routeTemplate(filename)), MAX_NAME);
    }

    // ---- public event API --------------------------------------------------------------------

    /** Rebuilds a minimal event from the allowlist; never mutates {@code raw}. */
    public static Map<String, Object> scrubEvent(Map<String, Object> raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (raw == null) {
            return out;
        }
        put(out, "event_id", matching(raw.get("event_id"), EVENT_ID));
        Object ts = raw.get("timestamp");
        if (ts instanceof Number) {
            out.put("timestamp", ts);
        } else {
            put(out, "timestamp", matching(ts, TIMESTAMP));
        }
        put(out, "platform", matching(raw.get("platform"), PLATFORM));
        Object level = raw.get("level");
        if (level instanceof String l && LEVELS.contains(l)) {
            out.put("level", l);
        }
        put(out, "logger", matching(raw.get("logger"), LOGGER));
        put(out, "release", matching(raw.get("release"), RELEASE));
        put(out, "environment", matching(raw.get("environment"), ENVIRONMENT));

        Object message = scrubMessage(raw.get("message"));
        if (message != null) {
            out.put("message", message);
        }
        Map<String, Object> exception = scrubExceptions(raw.get("exception"));
        if (exception != null) {
            out.put("exception", exception);
        }
        Map<String, Object> tags = scrubTags(raw.get("tags"));
        if (!tags.isEmpty()) {
            out.put("tags", tags);
        }
        Map<String, Object> contexts = scrubContexts(raw.get("contexts"));
        if (!contexts.isEmpty()) {
            out.put("contexts", contexts);
        }
        List<String> fingerprint = scrubFingerprint(raw.get("fingerprint"));
        if (!fingerprint.isEmpty()) {
            out.put("fingerprint", fingerprint);
        }
        Map<String, Object> sdk = scrubSdk(raw.get("sdk"));
        if (!sdk.isEmpty()) {
            out.put("sdk", sdk);
        }
        // Transaction names survive only when they are a route, and then only templated.
        if (raw.get("transaction") instanceof String t && t.startsWith("/")) {
            out.put("transaction", cut(scrubText(routeTemplate(t)), MAX_NAME));
        }
        return out;
    }

    // ---- event parts -------------------------------------------------------------------------

    private static Object scrubMessage(Object message) {
        if (message instanceof String s) {
            return cut(scrubText(s), MAX_VALUE);
        }
        if (message instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            if (m.get("formatted") instanceof String f) {
                out.put("formatted", cut(scrubText(f), MAX_VALUE));
            }
            if (m.get("message") instanceof String f) {
                out.put("message", cut(scrubText(f), MAX_VALUE));
            }
            return out.isEmpty() ? null : out;
        }
        return null;
    }

    private static Map<String, Object> scrubExceptions(Object exception) {
        List<?> values = null;
        if (exception instanceof Map<?, ?> m && m.get("values") instanceof List<?> l) {
            values = l;
        } else if (exception instanceof List<?> l) {
            values = l;
        }
        if (values == null) {
            return null;
        }
        List<Map<String, Object>> kept = new ArrayList<>();
        for (Object v : values) {
            if (kept.size() >= MAX_EXCEPTIONS) {
                break;
            }
            if (v instanceof Map<?, ?> ex) {
                kept.add(scrubException(ex));
            }
        }
        if (kept.isEmpty()) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("values", kept);
        return out;
    }

    private static Map<String, Object> scrubException(Map<?, ?> ex) {
        Map<String, Object> out = new LinkedHashMap<>();
        putText(out, "type", ex.get("type"), MAX_NAME);
        putText(out, "value", ex.get("value"), MAX_VALUE);
        putText(out, "module", ex.get("module"), MAX_NAME);
        if (ex.get("mechanism") instanceof Map<?, ?> mech) {
            Map<String, Object> m = new LinkedHashMap<>();
            put(m, "type", matching(mech.get("type"), MECHANISM_TYPE));
            if (mech.get("handled") instanceof Boolean b) {
                m.put("handled", b);
            }
            if (!m.isEmpty()) {
                out.put("mechanism", m);
            }
        }
        if (ex.get("stacktrace") instanceof Map<?, ?> st && st.get("frames") instanceof List<?> frames) {
            List<Map<String, Object>> kept = new ArrayList<>();
            int from = Math.max(0, frames.size() - MAX_FRAMES);
            for (Object f : frames.subList(from, frames.size())) {
                if (f instanceof Map<?, ?> fm) {
                    kept.add(scrubFrame(fm));
                }
            }
            if (!kept.isEmpty()) {
                Map<String, Object> stacktrace = new LinkedHashMap<>();
                stacktrace.put("frames", kept);
                out.put("stacktrace", stacktrace);
            }
        }
        return out;
    }

    private static Map<String, Object> scrubFrame(Map<?, ?> frame) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (frame.get("filename") instanceof String f) {
            out.put("filename", scrubFilename(f));
        }
        putText(out, "function", frame.get("function"), MAX_NAME);
        putText(out, "module", frame.get("module"), MAX_NAME);
        if (frame.get("lineno") instanceof Number n && n.intValue() >= 0) {
            out.put("lineno", n.intValue());
        }
        if (frame.get("colno") instanceof Number n && n.intValue() >= 0) {
            out.put("colno", n.intValue());
        }
        if (frame.get("in_app") instanceof Boolean b) {
            out.put("in_app", b);
        }
        return out;
    }

    private static Map<String, Object> scrubTags(Object tags) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (!(tags instanceof Map<?, ?> m)) {
            return out;
        }
        for (Map.Entry<?, ?> e : m.entrySet()) {
            if (!(e.getKey() instanceof String key) || !TAG_KEY.matcher(key).matches()) {
                continue;
            }
            Object v = e.getValue();
            if (!(v instanceof String || v instanceof Number || v instanceof Boolean)) {
                continue;
            }
            String value = String.valueOf(v);
            if ("route".equals(key)) {
                value = routeTemplate(value);
            }
            out.put(key, cut(scrubText(value), MAX_TAG_VALUE));
        }
        return out;
    }

    private static Map<String, Object> scrubContexts(Object contexts) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (!(contexts instanceof Map<?, ?> c)) {
            return out;
        }
        nameOnly(out, "os", c.get("os"), false);
        nameOnly(out, "browser", c.get("browser"), false);
        nameOnly(out, "runtime", c.get("runtime"), true);
        return out;
    }

    private static void nameOnly(Map<String, Object> out, String key, Object ctx, boolean withVersion) {
        if (!(ctx instanceof Map<?, ?> m)) {
            return;
        }
        Map<String, Object> kept = new LinkedHashMap<>();
        put(kept, "name", matching(m.get("name"), CONTEXT_NAME));
        if (withVersion) {
            put(kept, "version", matching(m.get("version"), VERSION));
        }
        if (!kept.isEmpty()) {
            out.put(key, kept);
        }
    }

    private static List<String> scrubFingerprint(Object fingerprint) {
        List<String> out = new ArrayList<>();
        if (fingerprint instanceof List<?> l) {
            for (Object o : l) {
                if (out.size() >= MAX_FINGERPRINT) {
                    break;
                }
                if (o instanceof String s) {
                    out.add(cut(scrubText(s), 100));
                }
            }
        }
        return out;
    }

    private static Map<String, Object> scrubSdk(Object sdk) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (sdk instanceof Map<?, ?> m) {
            put(out, "name", matching(m.get("name"), SDK_NAME));
            put(out, "version", matching(m.get("version"), VERSION));
        }
        return out;
    }

    // ---- helpers -----------------------------------------------------------------------------

    private static String replaceUrls(String s) {
        Matcher m = URL.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(scrubUrl(m.group())));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** Keeps scheme + host (no userinfo), templated path; drops query and fragment. */
    private static String scrubUrl(String url) {
        String noQuery = stripQueryAndFragment(url);
        Matcher origin = ORIGIN.matcher(noQuery);
        if (!origin.find()) {
            return "[url]";
        }
        String o = origin.group();
        int at = o.lastIndexOf('@');
        if (at >= 0) {
            o = o.substring(0, o.indexOf("://") + 3) + o.substring(at + 1);
        }
        return o + templatePath(noQuery.substring(origin.end()));
    }

    private static String stripQueryAndFragment(String s) {
        int cut = s.length();
        int q = s.indexOf('?');
        if (q >= 0) {
            cut = q;
        }
        int h = s.indexOf('#');
        if (h >= 0 && h < cut) {
            cut = h;
        }
        return s.substring(0, cut);
    }

    private static String templatePath(String path) {
        String[] segments = path.split("/", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) {
                sb.append('/');
            }
            sb.append(isIdSegment(segments[i]) ? ":id" : segments[i]);
        }
        return sb.toString();
    }

    private static boolean isIdSegment(String seg) {
        if (seg.isEmpty()) {
            return false;
        }
        if (seg.chars().allMatch(Character::isDigit)) {
            return true;
        }
        if (UUID_SEGMENT.matcher(seg).matches() || HEX_SEGMENT.matcher(seg).matches()) {
            return true;
        }
        return OPAQUE_SEGMENT.matcher(seg).matches() && seg.chars().anyMatch(Character::isDigit);
    }

    private static String matching(Object value, Pattern pattern) {
        return value instanceof String s && pattern.matcher(s).matches() ? s : null;
    }

    private static void put(Map<String, Object> out, String key, Object value) {
        if (value != null) {
            out.put(key, value);
        }
    }

    private static void putText(Map<String, Object> out, String key, Object value, int max) {
        if (value instanceof String s) {
            out.put(key, cut(scrubText(s), max));
        }
    }

    private static String cut(String s, int max) {
        return s != null && s.length() > max ? s.substring(0, max) : s;
    }
}
