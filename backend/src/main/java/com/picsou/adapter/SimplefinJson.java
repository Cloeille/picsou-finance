package com.picsou.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.picsou.exception.SyncException;
import com.picsou.port.SimplefinPort.SimplefinAccount;
import com.picsou.port.SimplefinPort.SimplefinAccountSet;
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

        Map<String, String> connectionNames = new HashMap<>();
        JsonNode connections = root.get("connections");
        if (connections != null && connections.isArray()) {
            for (JsonNode connection : connections) {
                String id = text(connection, "conn_id");
                if (id == null) continue;
                String name = text(connection, "org_name");
                if (name == null) name = text(connection, "name");
                connectionNames.put(id, name);
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

    private static void collectErrors(JsonNode node, List<String> errors) {
        if (node == null || !node.isArray()) return;
        for (JsonNode entry : node) {
            if (entry.isTextual()) {
                String text = entry.asText().trim();
                if (!text.isEmpty()) errors.add(text);
            } else if (entry.hasNonNull("msg")) {
                String text = entry.get("msg").asText().trim();
                if (!text.isEmpty()) errors.add(text);
            }
        }
    }

    private static SimplefinAccount parseAccount(JsonNode node, Map<String, String> connectionNames) {
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
        return new SimplefinAccount(
            externalAccountId(connId, id),
            connId == null ? null : connectionNames.get(connId),
            name == null ? "Account" : name,
            text(node, "currency"),
            balance,
            List.copyOf(transactions)
        );
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

    private static BigDecimal decimal(String value) {
        if (value == null) return null;
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
