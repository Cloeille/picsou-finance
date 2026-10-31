package com.picsou.adapter;

import com.picsou.config.EnableBankingConfigProvider;
import com.picsou.exception.SyncException;
import com.picsou.port.BankConnectorPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EnableBankingBankConnectorTest {

    @Mock EnableBankingConfigProvider configProvider;

    private EnableBankingBankConnector connector() {
        return new EnableBankingBankConnector(configProvider, "https://api.enablebanking.test");
    }

    // ─── Config-validation tests ──────────────────────────────────────────────

    @Test
    void searchInstitutions_missingPrivateKey_namesTheKey_notGenericNotConfigured() {
        // The reported bug: app-id/key-id present (in DB) but the key file is absent.
        lenient().when(configProvider.applicationId()).thenReturn(Optional.of("app-id"));
        lenient().when(configProvider.keyId()).thenReturn(Optional.of("key-id"));
        when(configProvider.privateKey()).thenReturn(Optional.empty());

        assertThatThrownBy(() -> connector().searchInstitutions("ci", "FR"))
            .isInstanceOf(SyncException.class)
            .hasMessageContaining("private key");
    }

    @Test
    void searchInstitutions_missingApplicationId_namesApplicationId() {
        lenient().when(configProvider.keyId()).thenReturn(Optional.of("key-id"));
        when(configProvider.applicationId()).thenReturn(Optional.empty());

        assertThatThrownBy(() -> connector().searchInstitutions("ci", "FR"))
            .isInstanceOf(SyncException.class)
            .hasMessageContaining("Application ID");
    }

    // ─── fetchBalances: per-account error isolation ────────────────────────────

    private static EnableBankingBankConnector.AccountPayload payload(
        String uid, String label, String iban, String currency, String amount
    ) {
        var balance = new EnableBankingBankConnector.BalancesResponse.BalanceItem(
            null, new EnableBankingBankConnector.BalancesResponse.BalanceAmount(amount, currency), "CLBD");
        var account = iban != null
            ? new EnableBankingBankConnector.AccountDetailsResponse.AccountIdentification(iban) : null;
        return new EnableBankingBankConnector.AccountPayload(uid,
            new EnableBankingBankConnector.BalancesResponse(List.of(balance)),
            new EnableBankingBankConnector.AccountDetailsResponse(account, null, label, null, null));
    }

    /**
     * When Enable Banking returns two accounts and fetching the first throws
     * (e.g. a 404 after a uid rotation as in EB v0.16.4 for Boursorama), the second account
     * must still be returned. Before the fix, the whole batch was aborted.
     */
    @Test
    void fetchBalances_oneAccountFails_otherAccountsStillReturned() {
        EnableBankingBankConnector underTest = spy(connector());
        String sessionId = "sess-xyz";

        // Session returns two account uids
        doReturn(List.of("uid-bad", "uid-good"))
            .when(underTest).fetchSessionAccountsWithRetry(sessionId);

        // uid-bad: the 404 after a uid rotation, as mapToSyncException surfaces it
        doThrow(new SyncException("Failed to fetch account balances: 404 Not Found"))
            .when(underTest).fetchAccountPayload("uid-bad");

        // uid-good: returns valid data
        doReturn(payload("uid-good", "Compte Courant", "FR7630006000011234567890189", "EUR", "1234.56"))
            .when(underTest).fetchAccountPayload("uid-good");

        List<BankConnectorPort.AccountData> result = underTest.fetchBalances(sessionId);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).externalId()).isEqualTo("uid-good");
        assertThat(result.get(0).balance()).isEqualByComparingTo("1234.56");
    }

    @Test
    void fetchBalances_everyAccountFails_rethrowsTheFirstFailureInsteadOfAnEmptyList() {
        EnableBankingBankConnector underTest = spy(connector());
        doReturn(List.of("uid-1", "uid-2")).when(underTest).fetchSessionAccountsWithRetry("sess-down");
        SyncException consentExpired = new SyncException("Consent expired", null, "CONSENT_EXPIRED");
        doThrow(consentExpired).when(underTest).fetchAccountPayload("uid-1");
        doThrow(new SyncException("503 Service Unavailable")).when(underTest).fetchAccountPayload("uid-2");

        assertThatThrownBy(() -> underTest.fetchBalances("sess-down")).isSameAs(consentExpired);
    }

    @Test
    void fetchBalances_failuresAcrossBothPasses_rethrowTheFirstFailure() {
        EnableBankingBankConnector underTest = spy(connector());
        doReturn(List.of("uid-unreadable", "uid-down")).when(underTest).fetchSessionAccountsWithRetry("sess-mixed");
        doReturn(payload("uid-unreadable", "Livret A", null, "EUR", "unreadable"))
            .when(underTest).fetchAccountPayload("uid-unreadable");
        SyncException down = new SyncException("503 Service Unavailable");
        doThrow(down).when(underTest).fetchAccountPayload("uid-down");

        assertThatThrownBy(() -> underTest.fetchBalances("sess-mixed")).isSameAs(down);
    }

    @Test
    void fetchBalances_malformedBalanceAmount_skipsThatAccount() {
        EnableBankingBankConnector underTest = spy(connector());
        doReturn(List.of("uid-bad", "uid-good")).when(underTest).fetchSessionAccountsWithRetry("sess-nfe");
        doReturn(payload("uid-bad", "Compte Courant", null, "EUR", "not-a-number"))
            .when(underTest).fetchAccountPayload("uid-bad");
        doReturn(payload("uid-good", "Livret A", null, "EUR", "500"))
            .when(underTest).fetchAccountPayload("uid-good");

        assertThat(underTest.fetchBalances("sess-nfe"))
            .singleElement().satisfies(account -> {
                assertThat(account.externalId()).isEqualTo("uid-good");
                assertThat(account.balance()).isEqualByComparingTo("500");
            });
    }

    @Test
    void fetchBalances_unexpectedFailure_propagatesEvenWhenAnotherAccountSucceeds() {
        EnableBankingBankConnector underTest = spy(connector());
        doReturn(List.of("uid-good", "uid-bug")).when(underTest).fetchSessionAccountsWithRetry("sess-bug");
        doReturn(payload("uid-good", "Livret A", null, "EUR", "500"))
            .when(underTest).fetchAccountPayload("uid-good");
        IllegalStateException bug = new IllegalStateException("unexpected");
        doThrow(bug).when(underTest).fetchAccountPayload("uid-bug");

        assertThatThrownBy(() -> underTest.fetchBalances("sess-bug")).isSameAs(bug);
    }

    /**
     * When all accounts succeed, all are returned — the normal path must not regress.
     * Each uid is fetched exactly once: Enable Banking enforces strict daily call limits.
     */
    @Test
    void fetchBalances_allAccountsSucceed_allReturnedAndEachUidFetchedOnce() {
        EnableBankingBankConnector underTest = spy(connector());
        String sessionId = "sess-ok";

        doReturn(List.of("uid-1", "uid-2")).when(underTest).fetchSessionAccountsWithRetry(sessionId);
        doReturn(payload("uid-1", "Livret A", "FR1234", "EUR", "500"))
            .when(underTest).fetchAccountPayload("uid-1");
        doReturn(payload("uid-2", "Compte Courant", null, "EUR", "1000"))
            .when(underTest).fetchAccountPayload("uid-2");

        List<BankConnectorPort.AccountData> result = underTest.fetchBalances(sessionId);

        assertThat(result).hasSize(2);
        assertThat(result).extracting(BankConnectorPort.AccountData::externalId)
            .containsExactly("uid-1", "uid-2");
        verify(underTest, times(1)).fetchAccountPayload("uid-1");
        verify(underTest, times(1)).fetchAccountPayload("uid-2");
    }

    /**
     * When the session returns an empty list (bank not yet linked), fetchBalances
     * returns empty — no exception thrown, caller decides what to do (retry logic).
     */
    @Test
    void fetchBalances_emptySession_returnsEmpty() {
        EnableBankingBankConnector underTest = spy(connector());

        doReturn(List.of()).when(underTest).fetchSessionAccountsWithRetry("sess-empty");

        List<BankConnectorPort.AccountData> result = underTest.fetchBalances("sess-empty");

        assertThat(result).isEmpty();
    }

    // ─── fetchBalances: null-currency tolerance ────────────────────────────────

    /**
     * Boursorama does not provide per-account currency (Enable Banking v0.16.4 change).
     * The balance must default to "EUR" rather than propagating null.
     */
    @Test
    void fetchBalances_nullCurrencyFromAccountData_cleansUpToEur() {
        EnableBankingBankConnector underTest = spy(connector());
        String sessionId = "sess-bourso";

        doReturn(List.of("uid-bourso")).when(underTest).fetchSessionAccountsWithRetry(sessionId);
        doReturn(payload("uid-bourso", "Compte Boursorama", "FR76300...", null /* no currency */, "2000"))
            .when(underTest).fetchAccountPayload("uid-bourso");

        List<BankConnectorPort.AccountData> result = underTest.fetchBalances(sessionId);

        assertThat(result).singleElement().satisfies(account -> {
            assertThat(account.externalId()).isEqualTo("uid-bourso");
            assertThat(account.currency()).isEqualTo("EUR");
        });
    }

    // ─── PSU type resolution ──────────────────────────────────────────────────

    /**
     * The reported bug: Swan is published under "business" only, so asking Enable
     * Banking for psu_type=personal made it invisible in the bank picker even though
     * the account existed and the credentials were valid.
     */
    @Test
    void resolvePsuType_businessWhenTheBankOffersNothingElse() {
        assertThat(EnableBankingBankConnector.resolvePsuType(List.of("business"))).isEqualTo("business");
    }

    @Test
    void resolvePsuType_prefersPersonalWheneverTheBankOffersIt() {
        assertThat(EnableBankingBankConnector.resolvePsuType(List.of("business", "personal"))).isEqualTo("personal");
    }

    /** An ASPSP that declares nothing is treated as retail — the pre-existing behaviour. */
    @Test
    void resolvePsuType_defaultsToPersonalWhenUnknown() {
        assertThat(EnableBankingBankConnector.resolvePsuType(null)).isEqualTo("personal");
        assertThat(EnableBankingBankConnector.resolvePsuType(List.of())).isEqualTo("personal");
    }

    /** An unrecognised type is passed through, not mistranslated into "business". */
    @Test
    void resolvePsuType_passesThroughAnUnknownProviderValue() {
        assertThat(EnableBankingBankConnector.resolvePsuType(List.of("corporate"))).isEqualTo("corporate");
    }

    // ─── Catalog mapping ──────────────────────────────────────────────────────

    @Test
    void toInstitutions_filtersByNameAndEncodesPsuTypeInTheId() {
        var swan = new EnableBankingBankConnector.AspspResponse(
            "Swan", "SWNBFR22", "https://logos.example/swan.png", "FR", List.of("business"));
        var bnp = new EnableBankingBankConnector.AspspResponse(
            "BNP Paribas", "BNPAFRPP", "https://logos.example/bnp.png", "FR", List.of("personal"));

        var results = EnableBankingBankConnector.toInstitutions(List.of(swan, bnp), "swan", "FR");

        assertThat(results).singleElement().satisfies(i -> {
            assertThat(i.id()).isEqualTo("Swan::FR::business");
            assertThat(i.name()).isEqualTo("Swan");
            assertThat(i.psuType()).isEqualTo("business");
            assertThat(i.country()).isEqualTo("FR");
        });
    }

    /** Enable Banking can list the same bank twice (different auth methods) -- one row, one React key. */
    @Test
    void toInstitutions_deduplicatesIdenticalCompositeIds() {
        var first = new EnableBankingBankConnector.AspspResponse(
            "Swan", "SWNBFR22", "https://logos.example/swan.png", "FR", List.of("business"));
        var duplicate = new EnableBankingBankConnector.AspspResponse(
            "Swan", "SWNBFR22", null, "FR", List.of("business"));

        var results = EnableBankingBankConnector.toInstitutions(List.of(first, duplicate), "swan", "FR");

        assertThat(results).hasSize(1);
        assertThat(results.get(0).logoUrl()).isEqualTo("https://logos.example/swan.png");
    }

    /**
     * The reverse order of the test above: keeping the first entry unconditionally would
     * publish a null logo for a bank whose second listing carries one, and the picker has
     * no second chance -- it renders whatever this returns.
     */
    @Test
    void toInstitutions_keepsTheLogoWhenOnlyTheLaterDuplicateCarriesOne() {
        var logoless = new EnableBankingBankConnector.AspspResponse(
            "Swan", "SWNBFR22", null, "FR", List.of("business"));
        var withLogo = new EnableBankingBankConnector.AspspResponse(
            "Swan", "SWNBFR22", "https://logos.example/swan.png", "FR", List.of("business"));

        var results = EnableBankingBankConnector.toInstitutions(List.of(logoless, withLogo), "swan", "FR");

        assertThat(results).hasSize(1);
        assertThat(results.get(0).logoUrl()).isEqualTo("https://logos.example/swan.png");
    }

    @Test
    void toInstitutions_fallsBackToTheRequestedCountryWhenTheAspspOmitsIt() {
        var noCountry = new EnableBankingBankConnector.AspspResponse(
            "Swan", null, null, null, List.of("business"));

        var results = EnableBankingBankConnector.toInstitutions(List.of(noCountry), "", "FR");

        assertThat(results).singleElement().satisfies(i -> {
            assertThat(i.country()).isEqualTo("FR");
            assertThat(i.id()).isEqualTo("Swan::FR::business");
        });
    }

    // ─── Institution id parsing ───────────────────────────────────────────────

    @Test
    void parseInstitutionId_readsTheThirdSegment() {
        var ref = EnableBankingBankConnector.parseInstitutionId("Swan::FR::business");

        assertThat(ref.bankName()).isEqualTo("Swan");
        assertThat(ref.country()).isEqualTo("FR");
        assertThat(ref.psuType()).isEqualTo("business");
    }

    /** Requisitions linked before PSU types existed store two segments only. */
    @Test
    void parseInstitutionId_defaultsLegacyTwoSegmentIdsToPersonal() {
        var ref = EnableBankingBankConnector.parseInstitutionId("BoursoBank::FR");

        assertThat(ref.bankName()).isEqualTo("BoursoBank");
        assertThat(ref.country()).isEqualTo("FR");
        assertThat(ref.psuType()).isEqualTo("personal");
    }

    /** The id comes off the wire and its PSU segment lands in an outbound provider request. */
    @Test
    void parseInstitutionId_coercesAnUnexpectedPsuSegmentToPersonal() {
        assertThat(EnableBankingBankConnector.parseInstitutionId("Swan::FR::../../etc").psuType())
            .isEqualTo("personal");
    }
}
