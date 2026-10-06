package com.picsou.imports.homebank;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.picsou.imports.homebank.ParsedHomeBankData.SourceAccount;
import static com.picsou.imports.homebank.ParsedHomeBankData.SourceCategory;
import static com.picsou.imports.homebank.ParsedHomeBankData.SourceTransaction;

/** Parses bounded, uncompressed desktop HomeBank QIF exports. */
public final class HomeBankQifParser {
    private static final int MAX_FILE_BYTES = 10 * 1024 * 1024;
    private static final int MAX_ACCOUNTS = 100;
    private static final int MAX_CATEGORIES = 200;
    private static final int MAX_TRANSACTIONS = 25_000;
    private static final int MAX_LINES = 500_000;
    private static final int MAX_TEXT = 255;
    private static final int MAX_ACCOUNT_CATEGORY_TEXT = 100;
    private static final UUID NAMESPACE = UUID.fromString("8a26b45a-967b-5c12-89d1-a9d775f19dc4");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("uuuu/MM/dd", Locale.ROOT)
            .withResolverStyle(ResolverStyle.STRICT);

    public ParsedHomeBankData parse(byte[] file, String currency) {
        if (file == null || file.length == 0 || file.length > MAX_FILE_BYTES) throw invalid();
        String currencyCode = validateCurrency(currency);
        String source = decode(file);
        if (source.startsWith("\uFEFF")) source = source.substring(1);

        Map<String, SourceAccount> accounts = new LinkedHashMap<>();
        Map<String, String> explicitCategoryTypes = new HashMap<>();
        Map<String, Boolean> categoryHasNegative = new HashMap<>();
        Map<String, Boolean> categoryHasPositive = new HashMap<>();
        Set<String> categoryPaths = new HashSet<>();
        List<RawTransaction> rawTransactions = new ArrayList<>();
        int normalizedRowCount = 0;
        String currentAccount = null;
        Section section = Section.NONE;
        List<String> accountFields = null;
        List<String> categoryFields = null;
        List<String> transactionFields = null;
        try (BufferedReader lines = new BufferedReader(new StringReader(source))) {
        String line;
        int lineCount = 0;
        while ((line = lines.readLine()) != null) {
            if (++lineCount > MAX_LINES || line.length() > MAX_TEXT + 1) throw invalid();
            if (line.isEmpty()) continue;
            if (line.startsWith("!")) {
                if (categoryFields != null && categoryFields.isEmpty()) categoryFields = null;
                if (accountFields != null || categoryFields != null || transactionFields != null) throw invalid();
                if (line.equals("!Account")) {
                    section = Section.ACCOUNT;
                    accountFields = new ArrayList<>();
                } else if (line.equals("!Type:Cat")) {
                    section = Section.CATEGORY;
                    categoryFields = new ArrayList<>();
                } else if (line.equals("!Type:Bank")) {
                    if (currentAccount == null) throw invalid();
                    section = Section.BANK;
                    transactionFields = null;
                } else if (line.equals("!Type:Cash") || line.equals("!Type:CCard")) {
                    if (currentAccount == null) throw invalid();
                    section = line.equals("!Type:Cash") ? Section.CASH : Section.CCARD;
                    transactionFields = null;
                } else {
                    throw invalid();
                }
                continue;
            }
            if (line.equals("^")) {
                if (accountFields != null) {
                    currentAccount = addAccount(accountFields, accounts, currencyCode);
                    accountFields = null;
                } else if (categoryFields != null) {
                    addCategoryDeclaration(categoryFields, explicitCategoryTypes, categoryPaths);
                    categoryFields = null;
                } else if (transactionFields != null) {
                    if (normalizedRowCount >= MAX_TRANSACTIONS) throw invalid();
                    RawTransaction transaction = parseTransaction(transactionFields, currentAccount,
                            MAX_TRANSACTIONS - normalizedRowCount);
                    normalizedRowCount += transaction.splits().isEmpty() ? 1 : transaction.splits().size();
                    rawTransactions.add(transaction);
                    if (rawTransactions.size() > MAX_TRANSACTIONS) throw invalid();
                    transactionFields = null;
                } else {
                    throw invalid();
                }
                continue;
            }
            if (line.length() < 1) throw invalid();
            if (accountFields != null || categoryFields != null || transactionFields != null
                    || ((section == Section.BANK || section == Section.CASH || section == Section.CCARD)
                    && transactionFields == null) || (section == Section.CATEGORY && categoryFields == null)) {
                if (!Character.isLetter(line.charAt(0)) && line.charAt(0) != '$') throw invalid();
                if (section == Section.CATEGORY && categoryFields == null) categoryFields = new ArrayList<>();
                if ((section == Section.BANK || section == Section.CASH || section == Section.CCARD)
                        && transactionFields == null) transactionFields = new ArrayList<>();
                (accountFields != null ? accountFields : categoryFields != null ? categoryFields : transactionFields).add(line);
            } else {
                throw invalid();
            }
        }
        } catch (IOException exception) {
            throw invalid();
        }
        if (accountFields != null || categoryFields != null || transactionFields != null) throw invalid();
        if (accounts.isEmpty()) throw invalid();

        List<Normalized> normalized = new ArrayList<>();
        for (RawTransaction transaction : rawTransactions) {
            if (transaction.splits().isEmpty()) {
                String category = transaction.transferTarget() == null ? transaction.category() : null;
                normalized.add(new Normalized(transaction, transaction.amount(), category, transaction.memo(), transaction.transferTarget()));
                noteCategory(category, transaction.amount(), categoryHasNegative, categoryHasPositive, categoryPaths);
            } else {
                BigDecimal sum = BigDecimal.ZERO;
                for (Split split : transaction.splits()) {
                    sum = sum.add(split.amount());
                    normalized.add(new Normalized(transaction, split.amount(), split.category(),
                            combinedMemo(transaction.memo(), split.memo()), null));
                    noteCategory(split.category(), split.amount(), categoryHasNegative, categoryHasPositive, categoryPaths);
                }
                if (sum.compareTo(transaction.amount()) != 0 || transaction.category() != null
                        || transaction.transferTarget() != null) throw invalid();
            }
        }
        if (normalized.size() > MAX_TRANSACTIONS) throw invalid();

        Map<String, SourceCategory> categories = buildCategories(explicitCategoryTypes, categoryHasNegative,
                categoryHasPositive);
        List<SourceTransaction> transactions = buildTransactions(normalized, accounts, categories, currencyCode);
        return new ParsedHomeBankData(new ArrayList<>(accounts.values()), new ArrayList<>(categories.values()), transactions);
    }

    private static String validateCurrency(String currency) {
        try {
            if (currency == null || !currency.matches("[A-Z]{3}")
                    || !Currency.getInstance(currency).getCurrencyCode().equals(currency)) throw invalid();
            return currency;
        } catch (IllegalArgumentException exception) {
            throw invalid();
        }
    }

    private static String decode(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException exception) {
            throw invalid();
        }
    }

    private static String addAccount(List<String> fields, Map<String, SourceAccount> accounts, String currency) {
        for (String field : fields) if (field.charAt(0) != 'N' && field.charAt(0) != 'T') throw invalid();
        String name = singleton(fields, 'N', true);
        String type = singleton(fields, 'T', true);
        if (name.isBlank() || name.length() > MAX_ACCOUNT_CATEGORY_TEXT
                || !(type.equalsIgnoreCase("Bank") || type.equalsIgnoreCase("Cash") || type.equalsIgnoreCase("CCard"))
                || accounts.containsKey(name) || accounts.size() >= MAX_ACCOUNTS) throw invalid();
        String id = stableId("account:" + name);
        String sourceType = type.equalsIgnoreCase("Cash") ? "cash"
                : type.equalsIgnoreCase("CCard") ? "creditcard" : "bank";
        accounts.put(name, new SourceAccount(id, name, null, sourceType, currency, BigDecimal.ZERO, false));
        return name;
    }

    private static void addCategoryDeclaration(List<String> fields, Map<String, String> types, Set<String> paths) {
        if (types.size() >= MAX_CATEGORIES) throw invalid();
        String name = singleton(fields, 'N', true);
        String income = null;
        for (String field : fields) {
            char tag = field.charAt(0);
            if (tag == 'I' || tag == 'E') {
                if (field.length() != 1 || income != null) throw invalid();
                income = tag == 'I' ? "income" : "expense";
            } else if (tag != 'N' && tag != 'D' && tag != 'T') {
                throw invalid();
            } else if (field.length() > 1 && field.substring(1).length() > MAX_TEXT) {
                throw invalid();
            }
        }
        if (income == null) throw invalid();
        String path = validateCategory(name);
        if (types.putIfAbsent(path, income) != null) throw invalid();
        addCategoryPaths(path, paths);
    }

    private static RawTransaction parseTransaction(List<String> fields, String account, int maxNormalizedRows) {
        for (String field : fields) {
            char tag = field.charAt(0);
            if (tag != 'D' && tag != 'T' && tag != 'P' && tag != 'M' && tag != 'L'
                    && tag != 'C' && tag != 'N' && tag != 'S' && tag != 'E' && tag != '$') throw invalid();
        }
        String dateText = singleton(fields, 'D', true);
        String amountText = singleton(fields, 'T', true);
        String payee = singleton(fields, 'P', false);
        String memo = singleton(fields, 'M', false);
        String category = singleton(fields, 'L', false);
        // Cleared status (C) and check number (N) are deliberately ignored and excluded from identity.
        singleton(fields, 'C', false);
        singleton(fields, 'N', false);
        LocalDate date;
        BigDecimal amount;
        try {
            date = LocalDate.parse(dateText, DATE);
        } catch (DateTimeParseException exception) {
            throw invalid();
        }
        try {
            if (!amountText.matches("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)")) throw invalid();
            amount = new BigDecimal(amountText);
            if (amount.precision() > 20 || amount.scale() > 8 || amount.scale() < -8) throw invalid();
        } catch (NumberFormatException exception) {
            throw invalid();
        }
        String transfer = null;
        if (category != null && category.startsWith("[") && category.endsWith("]")) {
            transfer = category.substring(1, category.length() - 1);
            if (transfer.isBlank()) throw invalid();
            category = null;
        }
        if (category != null) category = validateCategory(category);
        List<Split> splits = parseSplits(fields, maxNormalizedRows);
        if (account == null || (payee != null && payee.length() > MAX_TEXT) || (memo != null && memo.length() > MAX_TEXT)) {
            throw invalid();
        }
        return new RawTransaction(account, date, amount, payee, memo, category, transfer, splits);
    }

    private static List<Split> parseSplits(List<String> fields, int maxSplits) {
        List<Split> splits = new ArrayList<>();
        String splitCategory = null;
        String splitMemo = null;
        boolean started = false;
        boolean hasAmount = false;
        for (String field : fields) {
            char tag = field.charAt(0);
            if (tag == 'S') {
                if (started && !hasAmount) throw invalid();
                splitCategory = validateCategory(requiredValue(field));
                splitMemo = null;
                started = true;
                hasAmount = false;
            } else if (tag == 'E') {
                if (!started || hasAmount || splitMemo != null) throw invalid();
                splitMemo = limitedValue(field);
            } else if (tag == '$') {
                if (!started || hasAmount) throw invalid();
                if (splits.size() >= maxSplits) throw invalid();
                BigDecimal amount;
                String amountText = requiredValue(field);
                if (!amountText.matches("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)")) throw invalid();
                try { amount = new BigDecimal(amountText); }
                catch (NumberFormatException exception) { throw invalid(); }
                if (amount.precision() > 20 || amount.scale() > 8 || amount.scale() < -8) throw invalid();
                splits.add(new Split(splitCategory, splitMemo, amount));
                hasAmount = true;
            }
        }
        if (started && !hasAmount) throw invalid();
        return splits;
    }

    private static Map<String, SourceCategory> buildCategories(Map<String, String> explicit,
                                                                 Map<String, Boolean> negative,
                                                                 Map<String, Boolean> positive) {
        Set<String> paths = new HashSet<>(explicit.keySet());
        paths.addAll(negative.keySet());
        paths.addAll(positive.keySet());
        for (String path : new ArrayList<>(paths)) {
            for (String parent = parent(path); parent != null; parent = parent(parent)) paths.add(parent);
        }
        if (paths.size() > MAX_CATEGORIES) throw invalid();
        List<String> sorted = new ArrayList<>(paths);
        sorted.sort((left, right) -> Integer.compare(depth(left), depth(right)));
        Map<String, SourceCategory> result = new LinkedHashMap<>();
        Map<String, String> idByPath = new HashMap<>();
        for (String path : sorted) {
            String parentPath = parent(path);
            String id = stableId("category:" + path);
            boolean inferred = !explicit.containsKey(path);
            boolean income = inferred ? !Boolean.TRUE.equals(negative.get(path)) : explicit.get(path).equals("income");
            result.put(path, new SourceCategory(id, leaf(path), parentPath == null ? null : idByPath.get(parentPath),
                    income, inferred));
            idByPath.put(path, id);
        }
        return result;
    }

    private static List<SourceTransaction> buildTransactions(List<Normalized> normalized,
                                                               Map<String, SourceAccount> accounts,
                                                               Map<String, SourceCategory> categories,
                                                               String currency) {
        Map<String, Integer> occurrences = new HashMap<>();
        List<SourceTransaction> result = new ArrayList<>(normalized.size());
        for (Normalized row : normalized) {
            String transferId = row.transferTarget() == null ? null
                    : accountId(row.transferTarget(), accounts);
            if (row.transferTarget() != null && transferId == null) throw invalid();
            String categoryId = row.category() == null ? null
                    : categories.containsKey(row.category()) ? categories.get(row.category()).id() : null;
            String fingerprint = identity(row.source().account(), row.source().date().toString(),
                    row.amount().toPlainString(), row.source().payee(), row.memo(), row.category(),
                    row.transferTarget());
            int occurrence = occurrences.merge(fingerprint, 1, Integer::sum) - 1;
            String id = stableId("transaction:" + fingerprint + "\u001f" + occurrence);
            result.add(new SourceTransaction(id, accounts.get(row.source().account()).id(), row.source().date(),
                    row.amount(), currency, row.source().payee(), row.memo(), categoryId, transferId, false));
        }
        return result;
    }

    private static String accountId(String name, Map<String, SourceAccount> accounts) {
        SourceAccount account = accounts.get(name);
        return account == null ? null : account.id();
    }

    private static void noteCategory(String category, BigDecimal amount, Map<String, Boolean> negative,
                                     Map<String, Boolean> positive, Set<String> paths) {
        if (category == null) return;
        addCategoryPaths(category, paths);
        for (String path = category; path != null; path = parent(path)) {
            if (amount.signum() < 0) negative.put(path, true);
            else if (amount.signum() > 0) positive.put(path, true);
        }
    }

    private static void addCategoryPaths(String category, Set<String> paths) {
        for (String path = category; path != null; path = parent(path)) {
            paths.add(path);
            if (paths.size() > MAX_CATEGORIES) throw invalid();
        }
    }

    private static String combinedMemo(String parentMemo, String splitMemo) {
        boolean hasParent = parentMemo != null && !parentMemo.isEmpty();
        boolean hasSplit = splitMemo != null && !splitMemo.isEmpty();
        if (!hasParent) return hasSplit ? splitMemo : null;
        if (!hasSplit || parentMemo.equals(splitMemo)) return parentMemo;
        String combined = parentMemo + " — " + splitMemo;
        if (combined.length() > MAX_TEXT) throw invalid();
        return combined;
    }

    private static String singleton(List<String> fields, char tag, boolean required) {
        String value = null;
        int count = 0;
        for (String field : fields) {
            if (field.charAt(0) == tag) {
                count++;
                value = field.substring(1);
            }
        }
        if (count > 1 || (required && (count != 1 || value.isEmpty()))) throw invalid();
        if (value != null && value.length() > MAX_TEXT) throw invalid();
        return value == null || value.isEmpty() ? null : value;
    }

    private static String validateCategory(String value) {
        if (value == null || value.isBlank() || value.length() > MAX_TEXT) throw invalid();
        String[] parts = value.split(":", -1);
        if (parts.length > 16) throw invalid();
        for (String part : parts) if (part.isBlank() || part.length() > MAX_ACCOUNT_CATEGORY_TEXT) throw invalid();
        return String.join(":", parts);
    }

    private static String requiredValue(String field) {
        String value = field.substring(1);
        if (value.isBlank() || value.length() > MAX_TEXT) throw invalid();
        return value;
    }

    private static String limitedValue(String field) {
        String value = field.substring(1);
        if (value.length() > MAX_TEXT) throw invalid();
        return value;
    }

    private static int depth(String path) {
        int depth = 1;
        for (int i = 0; i < path.length(); i++) if (path.charAt(i) == ':') depth++;
        return depth;
    }

    private static String parent(String path) {
        int index = path.lastIndexOf(':');
        return index < 0 ? null : path.substring(0, index);
    }

    private static String leaf(String path) {
        int index = path.lastIndexOf(':');
        return index < 0 ? path : path.substring(index + 1);
    }

    private static String identity(String... values) {
        StringBuilder result = new StringBuilder();
        for (String value : values) {
            if (value == null) result.append("-1:");
            else result.append(value.length()).append(':').append(value);
        }
        return result.toString();
    }

    private static String stableId(String value) {
        return UUID.nameUUIDFromBytes((NAMESPACE + ":" + value).getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid or unsupported QIF file");
    }

    private enum Section { NONE, ACCOUNT, CATEGORY, BANK, CASH, CCARD }
    private record RawTransaction(String account, LocalDate date, BigDecimal amount, String payee, String memo,
                                  String category, String transferTarget, List<Split> splits) { }
    private record Split(String category, String memo, BigDecimal amount) { }
    private record Normalized(RawTransaction source, BigDecimal amount, String category, String memo,
                              String transferTarget) { }
}
