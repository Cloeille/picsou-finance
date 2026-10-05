package com.picsou.export.xlsx;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/**
 * Resolves a {@link LabelKey} to the heading the workbook prints.
 *
 * <p>The client sends the localized wording because the backend has no message bundle at all;
 * anything it does not send falls back to {@link LabelKey#englishDefault()}. That fallback is
 * what keeps {@code POST /api/accounts/export} usable without a browser.
 *
 * <p>Supplied values are untrusted text that ends up in cells, so each one is trimmed, stripped
 * of control characters (a newline in a header row breaks the grid a spreadsheet reader expects)
 * and capped. A value that sanitizes to nothing falls back to the default rather than printing
 * an empty header.
 */
public final class SheetLabels {

    /** Long enough for any real heading, short enough that a hostile payload cannot bloat cells. */
    private static final int MAX_LENGTH = 120;

    private final Map<LabelKey, String> resolved;

    private SheetLabels(Map<LabelKey, String> resolved) {
        this.resolved = resolved;
    }

    /**
     * @param supplied client labels keyed by {@link LabelKey} name in any case (the frontend
     *                 sends lowerCamelCase). Unknown keys are ignored, not rejected: a client
     *                 running ahead of the server must not fail the export.
     */
    public static SheetLabels of(Map<String, String> supplied) {
        Map<String, String> normalized = new java.util.HashMap<>();
        if (supplied != null) {
            supplied.forEach((k, v) -> {
                if (k != null) normalized.put(normalizeKey(k), v);
            });
        }

        Map<LabelKey, String> resolved = new EnumMap<>(LabelKey.class);
        for (LabelKey key : LabelKey.values()) {
            String sanitized = sanitize(normalized.get(normalizeKey(key.name())));
            resolved.put(key, sanitized == null ? key.englishDefault() : sanitized);
        }
        return new SheetLabels(resolved);
    }

    public static SheetLabels english() {
        return of(Map.of());
    }

    public String get(LabelKey key) {
        return resolved.get(key);
    }

    /** {@code ACCOUNT_NAME}, {@code accountName} and {@code account_name} are the same key. */
    private static String normalizeKey(String raw) {
        return raw.replace("_", "").replace("-", "").toLowerCase(Locale.ROOT);
    }

    private static String sanitize(String raw) {
        if (raw == null) return null;
        StringBuilder out = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length() && out.length() < MAX_LENGTH; i++) {
            char c = raw.charAt(i);
            // Keep printable characters only; a tab or newline would split the cell visually.
            if (!Character.isISOControl(c)) out.append(c);
        }
        String trimmed = out.toString().trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
