package com.picsou.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.picsou.exception.SyncException;
import com.picsou.port.SimplefinPort.SimplefinAccount;
import com.picsou.port.SimplefinPort.SimplefinAccountSet;
import com.picsou.port.SimplefinPort.SimplefinHolding;
import com.picsou.port.SimplefinPort.SimplefinTransaction;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** Parses a SimpleFIN account-set. Pending rows are dropped. */
final class SimplefinJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** Same {@code VARCHAR(255)} width as {@code BankTransactionImportService.EXTERNAL_ID_MAX}. */
    private static final int MAX_EXTERNAL_ID = 255;
    private static final int MAX_ERROR_CHARS = 300;

    private SimplefinJson() {}

    static SimplefinAccountSet parse(String json) {
        if (json == null || json.isBlank()) {
            throw new SyncException("SimpleFIN returned an empty response.");
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (Exception ex) {
            // No cause: Jackson's message quotes part of the body, which is account data.
            throw new SyncException("SimpleFIN returned a response Picsou could not read.");
        }
        if (root == null || !root.isObject()) {
            throw new SyncException("SimpleFIN returned a response Picsou could not read.");
        }

        List<String> errors = new ArrayList<>();
        collectErrors(root.get("errlist"), errors);
        collectErrors(root.get("errors"), errors);

        Map<String, Institution> connectionNames = new HashMap<>();
        JsonNode connections = root.get("connections");
        if (connections != null && connections.isArray()) {
            for (JsonNode connection : connections) {
                String id = text(connection, "conn_id");
                if (id == null) continue;
                String name = text(connection, "org_name");
                if (name == null) name = text(connection, "name");
                String domain = hostOf(text(connection, "org_url"));
                if (domain == null) domain = hostOf(text(connection, "url"));
                connectionNames.put(id, new Institution(name, domain));
            }
        }

        List<SimplefinAccount> accounts = new ArrayList<>();
        JsonNode accountsNode = root.get("accounts");
        if (accountsNode != null && accountsNode.isArray()) {
            for (JsonNode node : accountsNode) {
                SimplefinAccount account = parseAccount(node, connectionNames);
                if (account != null) accounts.add(account);
            }
        }
        return new SimplefinAccountSet(List.copyOf(errors), List.copyOf(accounts));
    }

    /** Bridge text reaches the UI and logs, so it is kept to one short line. */
    private static void collectErrors(JsonNode node, List<String> errors) {
        if (node == null || !node.isArray()) return;
        for (JsonNode entry : node) {
            JsonNode message = entry.isTextual() ? entry : entry.get("msg");
            if (message == null || message.isNull()) continue;
            String text = message.asText().replaceAll("[\\p{Cc}\\p{Zl}\\p{Zp}\\s]+", " ").trim();
            if (text.isEmpty()) continue;
            if (text.length() > MAX_ERROR_CHARS) text = clip(text, MAX_ERROR_CHARS) + "…";
            errors.add(text);
        }
    }

    /** What a {@code connections[]} entry says about the bank behind an account. */
    private record Institution(String name, String domain) {}

    /**
     * The bare host of a bank's website, or null. The value comes from the bridge and ends up in
     * a request path to the logo service, so only a plain public DNS name survives: no scheme
     * tricks, ports, userinfo, IP literals or single-label hosts.
     */
    static String hostOf(String raw) {
        if (raw == null) return null;
        String value = raw.trim().toLowerCase(java.util.Locale.ROOT);
        if (value.isEmpty() || value.length() > 300) return null;
        String host;
        if (value.contains("://")) {
            try {
                java.net.URI uri = java.net.URI.create(value);
                // Only the host is ever used, so the scheme and port say nothing that matters;
                // credentials in the URL are a sign of something else and are refused.
                if (!"https".equals(uri.getScheme()) && !"http".equals(uri.getScheme())) return null;
                if (uri.getUserInfo() != null) return null;
                host = uri.getHost();
            } catch (IllegalArgumentException ex) {
                return null;
            }
        } else {
            // A bare host, possibly with a path ("fidelity.com/login").
            host = value.split("[/?#]", 2)[0];
        }
        if (host == null) return null;
        if (host.endsWith(".")) host = host.substring(0, host.length() - 1); // a fully qualified name
        if (host.startsWith("www.")) host = host.substring(4);
        return HOST.matcher(host).matches() ? host : null;
    }

    private static final java.util.regex.Pattern HOST = java.util.regex.Pattern.compile(
        "(?=.{4,255}$)([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z][a-z0-9-]{1,62}");

    private static SimplefinAccount parseAccount(JsonNode node, Map<String, Institution> connectionNames) {
        String id = text(node, "id");
        if (id == null) return null;
        BigDecimal balance = decimal(text(node, "balance"));
        if (balance == null) return null;
        String connId = text(node, "conn_id");
        String name = text(node, "name");
        List<SimplefinTransaction> transactions = new ArrayList<>();
        JsonNode txNode = node.get("transactions");
        if (txNode != null && txNode.isArray()) {
            for (JsonNode tx : txNode) {
                SimplefinTransaction parsed = parseTransaction(tx);
                if (parsed != null) transactions.add(parsed);
            }
        }
        Institution institution = connId == null ? null : connectionNames.get(connId);
        Holdings holdings = parseHoldings(node.get("holdings"));
        return new SimplefinAccount(
            externalAccountId(connId, id),
            institution == null ? null : institution.name(),
            name == null ? "Account" : name,
            text(node, "currency"),
            balance,
            List.copyOf(transactions),
            institution == null ? null : institution.domain(),
            holdings.rows(),
            holdings.complete()
        );
    }

    private static final int MAX_HOLDINGS = 500;
    private static final int MAX_SYMBOL = 100;
    private static final int MAX_DESCRIPTION = 255;

    /** The readable rows, and whether they are the whole list the bridge sent. */
    private record Holdings(List<SimplefinHolding> rows, boolean complete) {}

    /**
     * Rows without a symbol or description, or without a numeric share count, are dropped: they
     * cannot be keyed or valued. A dropped row, or a list past {@link #MAX_HOLDINGS}, makes the
     * result incomplete, because replacing the stored portfolio with part of it would silently
     * delete the rest. Figures arrive as strings ("10.500") but a bridge may send numbers, so both
     * parse.
     */
    private static Holdings parseHoldings(JsonNode node) {
        if (node == null || !node.isArray()) return new Holdings(List.of(), true);
        List<SimplefinHolding> holdings = new ArrayList<>();
        boolean complete = node.size() <= MAX_HOLDINGS;
        int read = 0;
        for (JsonNode h : node) {
            if (read++ >= MAX_HOLDINGS) break;
            String symbol = clip(text(h, "symbol"), MAX_SYMBOL);
            String description = clip(text(h, "description"), MAX_DESCRIPTION);
            BigDecimal shares = decimal(text(h, "shares"));
            if (shares == null || (symbol == null && description == null)) {
                complete = false;
                continue;
            }
            holdings.add(new SimplefinHolding(
                symbol,
                description,
                text(h, "currency"),
                shares,
                decimal(text(h, "market_value")),
                decimal(text(h, "cost_basis")),
                decimal(text(h, "purchase_price"))
            ));
        }
        return new Holdings(List.copyOf(holdings), complete);
    }

    /** Stops on a character boundary, as {@code BankTransactionImportService.clip} does. */
    private static String clip(String value, int max) {
        if (value == null || value.length() <= max) return value;
        int end = Character.isHighSurrogate(value.charAt(max - 1)) ? max - 1 : max;
        return value.substring(0, end);
    }

    private static SimplefinTransaction parseTransaction(JsonNode node) {
        if (node.path("pending").asBoolean(false)) return null;
        long posted = node.path("posted").asLong(0);
        if (posted <= 0) return null;
        if (posted > 10_000_000_000L) posted = posted / 1000L;
        BigDecimal amount = decimal(text(node, "amount"));
        if (amount == null) return null;
        String description = text(node, "description");
        if (description == null) description = "Transaction";
        LocalDate date;
        try {
            date = Instant.ofEpochSecond(posted).atZone(ZoneOffset.UTC).toLocalDate();
        } catch (DateTimeException ex) {
            return null;
        }
        // PostgreSQL rejects a date past year 5874897. A bank posting outside this
        // span is a bad timestamp, including one the milliseconds guess did not fix.
        if (date.getYear() < 1900 || date.getYear() > 2200) return null;
        return new SimplefinTransaction(fit(text(node, "id")), date, amount, description);
    }

    /**
     * {@code sfin_} has to survive a hash. Account deletion recognises the connection
     * by that prefix, and a bare digest would leave the connection syncing forever.
     */
    static String externalAccountId(String connId, String accountId) {
        String conn = connId == null || connId.isBlank() ? "account" : connId;
        String raw = "sfin_" + conn + "_" + accountId;
        if (raw.length() <= MAX_EXTERNAL_ID) return raw;
        return "sfin_" + sha256(raw);
    }

    /** Keep ids inside {@code VARCHAR(255)}. A hash is stable across syncs. */
    private static String fit(String value) {
        if (value == null || value.isBlank()) return null;
        if (value.length() <= MAX_EXTERNAL_ID) return value;
        return sha256(value);
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return null;
        String text = value.asText().trim();
        return text.isEmpty() ? null : text;
    }

    /** No amount needs more than this many decimals or trailing zeros; a larger exponent is garbage. */
    private static final int MAX_SCALE = 30;

    /**
     * Null for text that is not a plain figure. An exponent such as {@code 1E+999999999} parses but
     * makes every later {@code divide} or {@code setScale} throw, which would fail the whole sync
     * for a value that is no money.
     */
    private static BigDecimal decimal(String value) {
        if (value == null) return null;
        try {
            BigDecimal parsed = new BigDecimal(value);
            return Math.abs(parsed.scale()) > MAX_SCALE ? null : parsed;
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
