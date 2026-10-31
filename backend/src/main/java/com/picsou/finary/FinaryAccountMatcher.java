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
 * accounts are skipped so two Finary rows cannot steal the same target, and
 * the name tier never offers an account another connector owns.
 */
public final class FinaryAccountMatcher {

    private FinaryAccountMatcher() {}

    public record Suggestion(Account account, String reason) {}

    /**
     * Every row's external-id match is reserved before any row is matched by name: otherwise an
     * earlier row with the same name takes an account a later row is already linked to.
     */
    public static List<Optional<Suggestion>> suggestAll(
            List<FinaryRow> rows, List<Account> existing) {
        Set<Long> claimed = new HashSet<>();
        List<Optional<Suggestion>> out = new ArrayList<>(rows.size());
        for (FinaryRow row : rows) {
            Optional<Suggestion> hit = byLinkedId(row, existing, claimed);
            hit.ifPresent(s -> claimed.add(s.account().getId()));
            out.add(hit);
        }
        for (int i = 0; i < rows.size(); i++) {
            if (out.get(i).isEmpty()) {
                Optional<Suggestion> hit = byName(rows.get(i), existing, claimed);
                hit.ifPresent(s -> claimed.add(s.account().getId()));
                out.set(i, hit);
            }
        }
        return out;
    }

    /** The first Picsou account two of these targets share, or null when every target is distinct. */
    public static Long sharedTarget(List<Long> targetAccountIds) {
        Set<Long> seen = new HashSet<>();
        for (Long id : targetAccountIds) {
            if (id != null && !seen.add(id)) {
                return id;
            }
        }
        return null;
    }

    static Optional<Suggestion> matchOne(FinaryRow row, List<Account> existing, Set<Long> claimed) {
        Optional<Suggestion> linked = byLinkedId(row, existing, claimed);
        return linked.isPresent() ? linked : byName(row, existing, claimed);
    }

    private static Optional<Suggestion> byLinkedId(FinaryRow row, List<Account> existing, Set<Long> claimed) {
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
        return Optional.empty();
    }

    private static Optional<Suggestion> byName(FinaryRow row, List<Account> existing, Set<Long> claimed) {
        String needle = normalize(row.name());
        if (needle.isEmpty()) {
            return Optional.empty();
        }
        List<Account> nameHits = existing.stream()
            .filter(a -> a.getId() != null && !claimed.contains(a.getId()))
            .filter(a -> !ownedByAnotherConnector(a))
            .filter(a -> needle.equals(normalize(a.getName())))
            .filter(a -> typesCompatible(row.category(), a.getType()))
            .toList();
        if (nameHits.size() == 1) {
            return Optional.of(new Suggestion(nameHits.get(0), "name+type"));
        }
        return Optional.empty();
    }

    /**
     * A synced account another connector (Trade Republic, Bourso, …) finds by its external id.
     * Finary accounts are created manual, so they never count. Mapping Finary onto such an account
     * must neither take its id nor replace its holdings, or that connector's next sync creates a
     * duplicate and the portfolio it owns is lost.
     */
    public static boolean ownedByAnotherConnector(Account account) {
        return !account.isManual() && account.getExternalAccountId() != null;
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
