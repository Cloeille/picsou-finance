package com.picsou.adapter;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.picsou.exception.SyncException;
import com.picsou.port.WisePort.WiseJar;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Parses the Wise profile list and balance list responses. */
final class WiseJson {

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    private static final String UNREADABLE = "Wise returned a response Picsou could not read.";

    private WiseJson() {}

    record Profile(long id, boolean business) {}

    static List<Profile> profiles(String json) {
        List<Profile> profiles = new ArrayList<>();
        for (JsonNode node : array(json)) {
            JsonNode id = node.get("id");
            if (id == null || !id.canConvertToLong()) continue;
            String type = text(node, "type");
            profiles.add(new Profile(id.asLong(), "BUSINESS".equalsIgnoreCase(type)));
        }
        return profiles;
    }

    /** Jars only. A row without an id, a currency or a readable amount is skipped. */
    static List<WiseJar> jars(String json, Profile profile) {
        List<WiseJar> jars = new ArrayList<>();
        for (JsonNode node : array(json)) {
            if (!"SAVINGS".equalsIgnoreCase(text(node, "type"))) continue;
            JsonNode id = node.get("id");
            String currency = text(node, "currency");
            BigDecimal worth = amount(node.get("totalWorth"));
            if (worth == null) worth = amount(node.get("amount"));
            if (id == null || !id.canConvertToLong() || currency == null || worth == null) continue;
            jars.add(new WiseJar(id.asLong(), profile.business(), text(node, "name"),
                currency.toUpperCase(Locale.ROOT), worth));
        }
        return jars;
    }

    private static JsonNode array(String json) {
        JsonNode root;
        try {
            root = json == null ? null : MAPPER.readTree(json);
        } catch (Exception ex) {
            // No cause: Jackson's message quotes part of the body, which is account data.
            throw new SyncException(UNREADABLE);
        }
        if (root == null || !root.isArray()) throw new SyncException(UNREADABLE);
        return root;
    }

    private static BigDecimal amount(JsonNode money) {
        if (money == null || !money.isObject()) return null;
        JsonNode value = money.get("value");
        if (value == null || !value.isNumber()) return null;
        return value.decimalValue();
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) return null;
        return value.asText().trim();
    }
}
