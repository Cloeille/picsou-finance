package com.picsou.finary;

import com.picsou.model.Account;
import com.picsou.model.AccountType;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Suggests which existing Picsou account a Finary row should map to.
 *
 * <p>Lookup order: current API external id, legacy xlsx external id, then a
 * unique (trimmed name + compatible type) pair. Already-claimed Picsou
 * accounts are skipped so two Finary rows cannot steal the same target.
 */
public final class FinaryAccountMatcher {

    private FinaryAccountMatcher() {}

    public record Suggestion(Account account, String reason) {}

    public static List<Optional<Suggestion>> suggestAll(
            List<FinaryRow> rows, List<Account> existing) {
        Set<Long> claimed = new HashSet<>();
        List<Optional<Suggestion>> out = new ArrayList<>(rows.size());
        for (FinaryRow row : rows) {
            Optional<Suggestion> hit = matchOne(row, existing, claimed);
            hit.ifPresent(s -> claimed.add(s.account().getId()));
            out.add(hit);
        }
        return out;
    }

    static Optional<Suggestion> matchOne(FinaryRow row, List<Account> existing, Set<Long> claimed) {
        String currentId = externalId(row.category(), row.finaryId());
        Optional<Suggestion> byCurrent = byExternalId(currentId, existing, claimed, "externalId");
        if (byCurrent.isPresent()) {
            return byCurrent;
        }

        if (row.slug() != null && !row.slug().isBlank()) {
            Optional<Suggestion> bySlug = byExternalId(
                externalId(row.category(), row.slug()), existing, claimed, "legacySlug");
            if (bySlug.isPresent()) {
                return bySlug;
            }
        }

        String needle = normalize(row.name());
        if (needle.isEmpty()) {
            return Optional.empty();
        }
        List<Account> nameHits = existing.stream()
            .filter(a -> a.getId() != null && !claimed.contains(a.getId()))
            .filter(a -> needle.equals(normalize(a.getName())))
            .filter(a -> typesCompatible(row.category(), a.getType()))
            .toList();
        if (nameHits.size() == 1) {
            return Optional.of(new Suggestion(nameHits.get(0), "name+type"));
        }
        return Optional.empty();
    }

    static String externalId(String category, String id) {
        return "finary_" + category + "_" + id;
    }

    static boolean typesCompatible(String category, AccountType type) {
        if (type == null || category == null) {
            return false;
        }
        String cat = category.toLowerCase(Locale.ROOT).replace(' ', '_');
        return switch (cat) {
            case "checkings", "checking" -> type == AccountType.CHECKING;
            case "savings", "fonds_euro", "fonds-euro" ->
                type == AccountType.SAVINGS
                    || type == AccountType.LIVRET_A
                    || type == AccountType.LDDS
                    || type == AccountType.LEP
                    || type == AccountType.LIVRET_JEUNE
                    || type == AccountType.PEL
                    || type == AccountType.CEL;
            case "investments" ->
                type == AccountType.COMPTE_TITRES
                    || type == AccountType.PEA
                    || type == AccountType.EMPLOYEE_SAVINGS;
            case "cryptos" -> type == AccountType.CRYPTO;
            case "loans", "credits" -> type == AccountType.LOAN || type == AccountType.OTHER;
            case "real_estates", "real-estate" -> type == AccountType.REAL_ESTATE;
            default -> type == AccountType.OTHER;
        };
    }

    private static Optional<Suggestion> byExternalId(
            String externalId, List<Account> existing, Set<Long> claimed, String reason) {
        return existing.stream()
            .filter(a -> a.getId() != null && !claimed.contains(a.getId()))
            .filter(a -> externalId.equals(a.getExternalAccountId()))
            .findFirst()
            .map(a -> new Suggestion(a, reason));
    }

    private static String normalize(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }

    public record FinaryRow(String finaryId, String name, String category, String slug) {}
}
