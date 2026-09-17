package com.picsou.finary;

import com.picsou.model.Account;
import com.picsou.model.AccountType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class FinaryAccountMatcherTest {

    @Test
    void prefersExternalIdOverName() {
        Account byId = account(1L, "Other name", AccountType.CHECKING, "finary_checkings_abc");
        Account byName = account(2L, "MR CHERRIER", AccountType.CHECKING, null);

        Optional<FinaryAccountMatcher.Suggestion> hit = FinaryAccountMatcher.suggestAll(
            List.of(new FinaryAccountMatcher.FinaryRow("abc", "MR CHERRIER", "checkings", null)),
            List.of(byId, byName)
        ).get(0);

        assertThat(hit).isPresent();
        assertThat(hit.get().account().getId()).isEqualTo(1L);
        assertThat(hit.get().reason()).isEqualTo("externalId");
    }

    @Test
    void matchesTrimmedNameAndCompatibleType() {
        Account lbp = account(10L, "MR CHERRIER CHRISTOPHE", AccountType.CHECKING, null);
        Account pea = account(11L, "PEA banque Postale ", AccountType.PEA, null);

        List<Optional<FinaryAccountMatcher.Suggestion>> hits = FinaryAccountMatcher.suggestAll(
            List.of(
                new FinaryAccountMatcher.FinaryRow("c1", "MR CHERRIER CHRISTOPHE", "checkings", null),
                new FinaryAccountMatcher.FinaryRow("p1", "PEA banque Postale", "investments", null)
            ),
            List.of(lbp, pea)
        );

        assertThat(hits.get(0)).isPresent();
        assertThat(hits.get(0).get().account().getId()).isEqualTo(10L);
        assertThat(hits.get(1)).isPresent();
        assertThat(hits.get(1).get().account().getId()).isEqualTo(11L);
        assertThat(hits.get(1).get().reason()).isEqualTo("name+type");
    }

    @Test
    void doesNotMapSamePicsouAccountTwice() {
        Account only = account(1L, "Coinbase", AccountType.CRYPTO, null);

        List<Optional<FinaryAccountMatcher.Suggestion>> hits = FinaryAccountMatcher.suggestAll(
            List.of(
                new FinaryAccountMatcher.FinaryRow("a", "Coinbase", "cryptos", null),
                new FinaryAccountMatcher.FinaryRow("b", "Coinbase", "cryptos", null)
            ),
            List.of(only)
        );

        assertThat(hits.get(0)).isPresent();
        assertThat(hits.get(1)).isEmpty();
    }

    @Test
    void distinguishesFortuneoAvByType() {
        Account titres = account(1L, "Fortuneo Assurance-vie", AccountType.COMPTE_TITRES, null);
        Account fonds = account(2L, "Fortuneo Assurance-vie", AccountType.SAVINGS, null);

        List<Optional<FinaryAccountMatcher.Suggestion>> hits = FinaryAccountMatcher.suggestAll(
            List.of(
                new FinaryAccountMatcher.FinaryRow("inv", "Fortuneo Assurance-vie", "investments", null),
                new FinaryAccountMatcher.FinaryRow("fe", "Fortuneo Assurance-vie", "fonds_euro", null)
            ),
            List.of(titres, fonds)
        );

        assertThat(hits.get(0).orElseThrow().account().getId()).isEqualTo(1L);
        assertThat(hits.get(1).orElseThrow().account().getId()).isEqualTo(2L);
    }

    @Test
    void leavesAmbiguousSameTypeUnmatched() {
        Account a = account(1L, "Livret A", AccountType.SAVINGS, null);
        Account b = account(2L, "Livret A", AccountType.SAVINGS, null);

        Optional<FinaryAccountMatcher.Suggestion> hit = FinaryAccountMatcher.suggestAll(
            List.of(new FinaryAccountMatcher.FinaryRow("x", "Livret A", "savings", null)),
            List.of(a, b)
        ).get(0);

        assertThat(hit).isEmpty();
    }

    private static Account account(Long id, String name, AccountType type, String externalId) {
        Account acc = Account.builder()
            .name(name)
            .type(type)
            .currency("EUR")
            .externalAccountId(externalId)
            .build();
        acc.setId(id);
        return acc;
    }
}
