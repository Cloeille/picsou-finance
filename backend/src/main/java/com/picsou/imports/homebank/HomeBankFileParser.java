package com.picsou.imports.homebank;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.springframework.stereotype.Component;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.UUID;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

import static com.picsou.imports.homebank.ParsedHomeBankData.SourceAccount;
import static com.picsou.imports.homebank.ParsedHomeBankData.SourceCategory;
import static com.picsou.imports.homebank.ParsedHomeBankData.SourceTransaction;

@Component
public final class HomeBankFileParser {
    private static final int MAX_FILE_BYTES = 10 * 1024 * 1024;
    private static final int MAX_JSON_BYTES = 50 * 1024 * 1024;
    private static final int MAX_PASSWORD_CHARS = 1024;
    private static final int MAX_ROW_TOKENS = 256;
    private static final int MAX_ACCOUNTS = 100;
    private static final int MAX_CATEGORIES = 200;
    private static final int MAX_PAYEES = 10_000;
    private static final int MAX_TRANSACTIONS = 25_000;
    private static final int SALT_BYTES = 32;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int PBKDF2_ITERATIONS = 600_000;
    private static final Set<String> ACCOUNT_TYPES = Set.of("bank", "savings", "cash", "creditcard", "asset", "liability");
    private static final Semaphore DECODE_SLOTS = new Semaphore(2);
    private static final ObjectMapper STRICT_MAPPER = mapper();

    public HomeBankFileParser(ObjectMapper ignoredMapper) { }

    public HomeBankFileParser() { }

    private static ObjectMapper mapper() {
        JsonFactory factory = JsonFactory.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .streamReadConstraints(StreamReadConstraints.builder()
                        .maxNestingDepth(64).maxStringLength(4096).maxNameLength(256)
                        .maxNumberLength(64).maxDocumentLength(MAX_JSON_BYTES).maxTokenCount(2_000_000).build())
                .build();
        return JsonMapper.builder(factory).enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();
    }

    public ParsedHomeBankData parse(byte[] file, String filename, String password) {
        return parse(file, filename, password, null);
    }

    public ParsedHomeBankData parse(byte[] file, String filename, String password, String currency) {
        if (file == null || file.length == 0 || file.length > MAX_FILE_BYTES) {
            throw invalid("HomeBank file size is invalid");
        }
        if (filename == null || !(filename.toLowerCase(Locale.ROOT).endsWith(".hbk")
                || filename.toLowerCase(Locale.ROOT).endsWith(".hbexport")
                || filename.toLowerCase(Locale.ROOT).endsWith(".qif"))) {
            throw invalid("Unsupported HomeBank file extension");
        }
        if (password != null && password.length() > MAX_PASSWORD_CHARS) throw invalid("HomeBank password is too long");
        if (!DECODE_SLOTS.tryAcquire()) throw invalid("Too many HomeBank files are being decoded; retry shortly");
        try {
            if (filename.toLowerCase(Locale.ROOT).endsWith(".qif")) {
                return new HomeBankQifParser().parse(file, currency);
            }
            byte[] compressed = filename.toLowerCase(Locale.ROOT).endsWith(".hbexport")
                    ? decrypt(file, password) : file;
            byte[] json = inflate(compressed);
            try {
                return parseJson(json);
            } finally {
                java.util.Arrays.fill(json, (byte) 0);
                if (compressed != file) java.util.Arrays.fill(compressed, (byte) 0);
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            // Never forward parser, crypto, source-data, or provider details to callers.
            throw invalid("Invalid or corrupted HomeBank file");
        } finally {
            DECODE_SLOTS.release();
        }
    }

    private byte[] decrypt(byte[] file, String password) {
        if (password == null || password.isEmpty()) throw invalid("Password required for HomeBank export");
        if (file.length < 4 + SALT_BYTES + NONCE_BYTES + 16) throw invalid("Invalid or corrupted HomeBank export");
        int version = ByteBuffer.wrap(file, 0, 4).order(ByteOrder.BIG_ENDIAN).getInt();
        if (version != 1) throw invalid("Unsupported HomeBank export encryption version");
        byte[] salt = java.util.Arrays.copyOfRange(file, 4, 4 + SALT_BYTES);
        byte[] nonce = java.util.Arrays.copyOfRange(file, 4 + SALT_BYTES, 4 + SALT_BYTES + NONCE_BYTES);
        byte[] ciphertext = java.util.Arrays.copyOfRange(file, 4 + SALT_BYTES + NONCE_BYTES, file.length);
        char[] chars = password.toCharArray();
        byte[] keyBytes = null;
        try {
            PBEKeySpec spec = new PBEKeySpec(chars, salt, PBKDF2_ITERATIONS, 256);
            try {
                keyBytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            } finally {
                spec.clearPassword();
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(keyBytes, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
            return cipher.doFinal(ciphertext);
        } catch (AEADBadTagException e) {
            throw invalid("Incorrect password or corrupted HomeBank export");
        } catch (Exception e) {
            throw invalid("Incorrect password or corrupted HomeBank export");
        } finally {
            java.util.Arrays.fill(chars, '\0');
            java.util.Arrays.fill(salt, (byte) 0);
            java.util.Arrays.fill(nonce, (byte) 0);
            java.util.Arrays.fill(ciphertext, (byte) 0);
            if (keyBytes != null) java.util.Arrays.fill(keyBytes, (byte) 0);
        }
    }

    private byte[] inflate(byte[] compressed) throws DataFormatException {
        Inflater inflater = new Inflater(true);
        inflater.setInput(compressed);
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(compressed.length * 4, 1024 * 1024));
        byte[] buffer = new byte[8192];
        try {
            while (!inflater.finished()) {
                int count = inflater.inflate(buffer);
                if (count > 0) {
                    if (output.size() + count > MAX_JSON_BYTES) throw invalid("HomeBank export exceeds the decompressed size limit");
                    output.write(buffer, 0, count);
                } else if (inflater.needsDictionary() || inflater.needsInput()) {
                    throw invalid("Invalid or corrupted HomeBank file");
                } else {
                    throw invalid("Invalid or corrupted HomeBank file");
                }
            }
            if (inflater.getRemaining() != 0) throw invalid("Trailing data after HomeBank compressed payload");
            return output.toByteArray();
        } finally {
            inflater.end();
        }
    }

    private ParsedHomeBankData parseJson(byte[] json) throws IOException {
        Map<String, SourceAccount> accounts = new LinkedHashMap<>();
        Map<String, SourceCategory> categories = new LinkedHashMap<>();
        Map<String, String> payees = new LinkedHashMap<>();
        List<PendingTransaction> transactions = new ArrayList<>();
        boolean manifestSeen = false;
        boolean dataSeen = false;
        boolean accountsSeen = false;
        boolean categoriesSeen = false;
        boolean payeesSeen = false;
        boolean transactionsSeen = false;
        try (JsonParser parser = STRICT_MAPPER.getFactory().createParser(json)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) throw invalid("Invalid HomeBank JSON root");
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                if (parser.currentToken() != JsonToken.FIELD_NAME) throw invalid("Invalid HomeBank JSON root");
                String field = parser.currentName();
                JsonToken value = parser.nextToken();
                if ("manifest".equals(field)) {
                    if (manifestSeen) throw invalid("Duplicate HomeBank field");
                    manifestSeen = true;
                    JsonNode manifest = readBoundedNode(parser, value);
                    if (!manifest.isObject() || intValue(required(manifest, "schemaVersion"), "schemaVersion") != 3
                            || !"iOS".equals(text(required(manifest, "platform"), "platform"))) {
                        throw invalid("Unsupported HomeBank export schema");
                    }
                } else if ("data".equals(field)) {
                    if (dataSeen) throw invalid("Duplicate HomeBank field");
                    dataSeen = true;
                    if (value != JsonToken.START_OBJECT) throw invalid("Invalid HomeBank field");
                    while (parser.nextToken() != JsonToken.END_OBJECT) {
                        if (parser.currentToken() != JsonToken.FIELD_NAME) throw invalid("Invalid HomeBank field");
                        String list = parser.currentName();
                        JsonToken listToken = parser.nextToken();
                        if ("accounts".equals(list)) {
                            if (accountsSeen) throw invalid("Duplicate HomeBank field");
                            accountsSeen = true;
                            readArray(parser, listToken, MAX_ACCOUNTS, row -> addAccount(accounts, row));
                        } else if ("categories".equals(list)) {
                            if (categoriesSeen) throw invalid("Duplicate HomeBank field");
                            categoriesSeen = true;
                            readArray(parser, listToken, MAX_CATEGORIES, row -> addCategory(categories, row));
                        } else if ("payees".equals(list)) {
                            if (payeesSeen) throw invalid("Duplicate HomeBank field");
                            payeesSeen = true;
                            readArray(parser, listToken, MAX_PAYEES, row -> addPayee(payees, row));
                        } else if ("transactions".equals(list)) {
                            if (transactionsSeen) throw invalid("Duplicate HomeBank field");
                            transactionsSeen = true;
                            readArray(parser, listToken, MAX_TRANSACTIONS,
                                    row -> transactions.add(parseTransaction(row)));
                        } else {
                            parser.skipChildren();
                        }
                    }
                } else {
                    parser.skipChildren();
                }
            }
            if (parser.nextToken() != null) throw invalid("Trailing data after HomeBank JSON");
        }
        if (!manifestSeen || !dataSeen || !accountsSeen || !categoriesSeen || !payeesSeen || !transactionsSeen) {
            throw invalid("Missing HomeBank field");
        }
        if (accounts.isEmpty()) throw invalid("HomeBank export has no accounts");
        validateCategoryParents(categories);
        Set<String> transactionIds = new HashSet<>();
        List<SourceTransaction> parsedTransactions = new ArrayList<>(transactions.size());
        for (PendingTransaction pending : transactions) {
            SourceTransaction transaction = pending.transaction();
            if (!transactionIds.add(transaction.id())) throw invalid("Duplicate transaction id");
            String accountId = transaction.accountId();
            SourceAccount account = accounts.get(accountId);
            if (account == null) throw invalid("Unknown transaction account");
            if (!account.currency().equals(transaction.currency())) throw invalid("Transaction currency mismatch");
            if (transaction.categoryId() != null && !categories.containsKey(transaction.categoryId())) {
                throw invalid("Unknown transaction category");
            }
            String payee = null;
            if (pending.payeeId() != null) {
                payee = payees.get(pending.payeeId());
                if (payee == null) throw invalid("Unknown transaction payee");
            }
            if (transaction.transferAccountId() != null && !accounts.containsKey(transaction.transferAccountId())) {
                throw invalid("Unknown transfer account");
            }
            parsedTransactions.add(new SourceTransaction(transaction.id(), transaction.accountId(), transaction.date(),
                    transaction.amount(), transaction.currency(), payee, transaction.notes(), transaction.categoryId(),
                    transaction.transferAccountId(), transaction.forecast()));
        }
        return new ParsedHomeBankData(new ArrayList<>(accounts.values()), new ArrayList<>(categories.values()), parsedTransactions);
    }

    private static void readArray(JsonParser parser, JsonToken token, int max, java.util.function.Consumer<JsonNode> consumer)
            throws IOException {
        if (token != JsonToken.START_ARRAY) throw invalid("Invalid HomeBank list");
        int count = 0;
        while (parser.nextToken() != JsonToken.END_ARRAY) {
            if (++count > max) throw invalid("HomeBank list exceeds the supported limit");
            consumer.accept(readBoundedNode(parser, parser.currentToken()));
        }
    }

    private static JsonNode readBoundedNode(JsonParser parser, JsonToken token) throws IOException {
        return readBoundedNode(parser, token, new int[]{0}, 0);
    }

    private static JsonNode readBoundedNode(JsonParser parser, JsonToken token, int[] tokens, int depth) throws IOException {
        if (++tokens[0] > MAX_ROW_TOKENS || depth > 64) throw invalid("HomeBank record exceeds the supported complexity");
        if (token == JsonToken.START_OBJECT) {
            ObjectNode object = JsonNodeFactory.instance.objectNode();
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                if (parser.currentToken() != JsonToken.FIELD_NAME) throw invalid("Invalid HomeBank record");
                if (++tokens[0] > MAX_ROW_TOKENS) throw invalid("HomeBank record exceeds the supported complexity");
                String name = parser.currentName();
                JsonToken child = parser.nextToken();
                object.set(name, readBoundedNode(parser, child, tokens, depth + 1));
            }
            return object;
        }
        if (token == JsonToken.START_ARRAY) {
            ArrayNode array = JsonNodeFactory.instance.arrayNode();
            while (parser.nextToken() != JsonToken.END_ARRAY) {
                array.add(readBoundedNode(parser, parser.currentToken(), tokens, depth + 1));
            }
            return array;
        }
        if (token == JsonToken.VALUE_STRING) return JsonNodeFactory.instance.textNode(parser.getText());
        if (token == JsonToken.VALUE_NUMBER_INT) return JsonNodeFactory.instance.numberNode(parser.getBigIntegerValue());
        if (token == JsonToken.VALUE_NUMBER_FLOAT) return JsonNodeFactory.instance.numberNode(parser.getDecimalValue());
        if (token == JsonToken.VALUE_TRUE) return JsonNodeFactory.instance.booleanNode(true);
        if (token == JsonToken.VALUE_FALSE) return JsonNodeFactory.instance.booleanNode(false);
        if (token == JsonToken.VALUE_NULL) return JsonNodeFactory.instance.nullNode();
        throw invalid("Invalid HomeBank record");
    }

    private static void addAccount(Map<String, SourceAccount> accounts, JsonNode node) {
        String id = id(required(node, "id"));
        String name = limitedText(required(node, "name"), "account name", 100);
        String type = text(required(node, "type"), "account type");
        if (!ACCOUNT_TYPES.contains(type)) throw invalid("Unsupported HomeBank account type");
        String currency = currency(requiredObject(node, "currency"), "code");
        JsonNode initial = requiredObject(node, "initialAmount");
        String initialCurrency = text(required(initial, "currencyCode"), "currencyCode");
        if (!currency.equals(initialCurrency)) throw invalid("Account balance currency mismatch");
        SourceAccount account = new SourceAccount(id, name, optionalLimitedText(node, "institution", 100), type,
                currency, money(required(initial, "amount")), bool(required(node, "isClosed"), "isClosed"));
        unique(accounts, id, account, "account");
    }

    private static void addCategory(Map<String, SourceCategory> categories, JsonNode node) {
        String id = id(required(node, "id"));
        SourceCategory category = new SourceCategory(id, limitedText(required(node, "name"), "category name", 100),
                optionalId(node, "parentID"), bool(required(node, "isIncomeType"), "isIncomeType"));
        unique(categories, id, category, "category");
    }

    private static void addPayee(Map<String, String> payees, JsonNode node) {
        String id = id(required(node, "id"));
        unique(payees, id, limitedText(required(node, "name"), "payee name", 255), "payee");
    }

    private static PendingTransaction parseTransaction(JsonNode node) {
        String transactionId = id(required(node, "id"));
        String accountId = id(required(node, "accountID"));
        JsonNode amountNode = requiredObject(node, "amount");
        BigDecimal amount = money(required(amountNode, "amount"));
        String amountCurrency = text(required(amountNode, "currencyCode"), "transaction currency");
        JsonNode dateNode = requiredObject(node, "date");
        LocalDate date;
        try {
            date = LocalDate.of(intValue(required(dateNode, "year"), "year"),
                    intValue(required(dateNode, "month"), "month"), intValue(required(dateNode, "day"), "day"));
        } catch (DateTimeException e) { throw invalid("Invalid transaction date"); }
        String categoryId = null;
        JsonNode categoryNode = node.get("category");
        if (categoryNode != null && !categoryNode.isNull()) {
            if (!categoryNode.isObject()) throw invalid("Invalid transaction category");
            categoryId = optionalId(categoryNode, "id");
            if (categoryId == null) throw invalid("Unknown transaction category");
        }
        String payeeId = null;
        JsonNode payeeNode = node.get("payee");
        if (payeeNode != null && !payeeNode.isNull()) {
            if (!payeeNode.isObject()) throw invalid("Invalid transaction payee");
            payeeId = optionalId(payeeNode, "id");
            if (payeeId == null) throw invalid("Unknown transaction payee");
        }
        String transfer = optionalId(node, "transferAccountID");
        JsonNode splits = node.get("splits");
        if (splits != null && !splits.isNull() && (!splits.isArray() || !splits.isEmpty())) {
            throw invalid("Split transactions are not supported");
        }
        SourceTransaction source = new SourceTransaction(transactionId, accountId, date, amount, amountCurrency, null,
                optionalLimitedText(node, "memo", 255), categoryId, transfer,
                bool(required(node, "isForecast"), "isForecast"));
        return new PendingTransaction(source, payeeId);
    }

    private record PendingTransaction(SourceTransaction transaction, String payeeId) { }

    private static void validateCategoryParents(Map<String, SourceCategory> categories) {
        for (SourceCategory category : categories.values()) {
            if (category.parentId() != null) {
                SourceCategory parent = categories.get(category.parentId());
                if (parent == null || parent.parentId() != null) throw invalid("Invalid category parent");
            }
        }
    }


    private static JsonNode requiredObject(JsonNode parent, String field) {
        JsonNode value = required(parent, field);
        if (!value.isObject()) throw invalid("Invalid HomeBank field");
        return value;
    }

    private static JsonNode required(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || value.isNull()) throw invalid("Missing HomeBank field");
        return value;
    }

    private static String text(JsonNode value, String field) {
        if (!value.isTextual() || value.textValue().isEmpty()) throw invalid("Invalid " + field);
        return value.textValue();
    }

    private static String limitedText(JsonNode value, String field, int maxChars) {
        String result = text(value, field);
        if (result.length() > maxChars) throw invalid("Invalid " + field);
        return result;
    }

    private static String optionalLimitedText(JsonNode parent, String field, int maxChars) {
        JsonNode value = parent.get(field);
        if (value == null || value.isNull()) return null;
        return limitedText(value, field, maxChars);
    }

    private static boolean bool(JsonNode value, String field) {
        if (!value.isBoolean()) throw invalid("Invalid " + field);
        return value.booleanValue();
    }

    private static int intValue(JsonNode value, String field) {
        if (!value.isIntegralNumber() || !value.canConvertToInt()) throw invalid("Invalid " + field);
        return value.intValue();
    }

    private static String currency(JsonNode object, String field) {
        String value = text(required(object, field), "currency");
        if (!value.matches("[A-Z]{3}")) throw invalid("Invalid currency code");
        return value;
    }

    private static BigDecimal money(JsonNode node) {
        if (!node.isNumber()) throw invalid("Invalid monetary amount");
        BigDecimal value = node.decimalValue();
        int integerDigits = Math.max(0, value.precision() - value.scale());
        if (integerDigits > 12 || value.scale() > 8) throw invalid("Monetary amount exceeds supported precision");
        return value;
    }

    private static String id(JsonNode node) {
        String value = text(node, "id");
        if (!value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
            throw invalid("Invalid HomeBank identifier");
        }
        try { return UUID.fromString(value).toString(); }
        catch (IllegalArgumentException e) { throw invalid("Invalid HomeBank identifier"); }
    }

    private static String optionalId(JsonNode parent, String field) {
        JsonNode node = parent.get(field);
        if (node == null || node.isNull()) return null;
        return id(node);
    }

    private static <T> void unique(Map<String, T> map, String id, T value, String kind) {
        if (map.putIfAbsent(id, value) != null) throw invalid("Duplicate " + kind + " id");
    }

    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
}
