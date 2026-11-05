package com.picsou.service;

import com.picsou.exception.SyncException;
import com.picsou.model.Account;
import com.picsou.model.AccountHolding;
import com.picsou.model.AccountType;
import com.picsou.model.FamilyMember;
import com.picsou.model.RecurringCadence;
import com.picsou.model.WealthTier;
import com.picsou.port.CorumErrorCode;
import com.picsou.port.CorumPort;
import com.picsou.repository.AccountHoldingRepository;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.FamilyMemberRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CorumLifeWriterTest {
    private static final Instant SYNCED_AT = Instant.parse("2026-11-05T10:00:00Z");
    private static final LocalDate VALUED_ON = LocalDate.of(2026, 11, 4);
    private static final LocalDate OPENED_ON = LocalDate.of(2024, 1, 15);

    @Mock AccountRepository accountRepository;
    @Mock AccountHoldingRepository holdingRepository;
    @Mock FamilyMemberRepository memberRepository;
    @Mock AccountService accountService;
    @Mock CorumLifeOperationWriter operationWriter;
    @InjectMocks CorumLifeWriter writer;

    @org.junit.jupiter.api.BeforeEach
    void operationsKeptByDefault() {
        lenient().when(operationWriter.reconcile(any(), any(), any(), any()))
            .thenReturn(CorumLifeOperationWriter.Result.KEPT);
    }

    // ─── validate ─────────────────────────────────────────────────────────────

    @Test
    void validate_acceptsLinesThatAddUpToTheContract() {
        assertThatCode(() -> writer.validate(List.of(contract("1001")))).doesNotThrowAnyException();
    }

    @Test
    void validate_acceptsNoLifeContract() {
        assertThatCode(() -> writer.validate(List.of())).doesNotThrowAnyException();
    }

    @Test
    void validate_refusesAMissingContractList() {
        assertRefused(null, CorumErrorCode.INVALID_DATA);
    }

    @Test
    void validate_refusesTwoReadsOfOneContract() {
        assertRefused(List.of(contract("1001"), contract("1001")), CorumErrorCode.INVALID_DATA);
    }

    @Test
    void validate_refusesAContractTheSidecarDidNotCertify() {
        assertRefused(List.of(new CorumPort.LifeContract("1001", "EUR", new BigDecimal("1534.37"),
            VALUED_ON, 3, null, null, null, null, false, holdings(), null)), CorumErrorCode.PORTFOLIO_INCOMPLETE);
    }

    @Test
    void validate_refusesAContractNotInEuros() {
        assertRefused(List.of(new CorumPort.LifeContract("1001", "USD", new BigDecimal("1534.37"),
            VALUED_ON, 3, null, null, null, null, true, holdings(), null)), CorumErrorCode.INVALID_DATA);
    }

    @Test
    void validate_refusesANegativeUnitCount() {
        assertRefused(List.of(contractWith("518.61", 1,
            unitLinked("XL", "-1", "518.61", "480"))), CorumErrorCode.INVALID_DATA);
    }

    /** "xl" and "XL" would become one ticker and overwrite each other. */
    @Test
    void validate_refusesTwoLinesWithOneTicker() {
        assertRefused(List.of(contractWith("1037.22", 2,
            unitLinked("XL", "2.65955", "518.61", "480"),
            unitLinked("xl", "2.65955", "518.61", "480"))), CorumErrorCode.INVALID_DATA);
    }

    @Test
    void validate_refusesFewerRawLinesThanHoldings() {
        assertRefused(List.of(contractWith("1534.37", 1, holdings().toArray(CorumPort.LifeHolding[]::new))),
            CorumErrorCode.INVALID_DATA);
    }

    @Test
    void validate_refusesLinesThatMissTheContractTotal() {
        // 3 raw lines: the allowance is the 0.05 floor, and 0.06 is missing.
        assertRefused(List.of(contractWith("1534.43", 3, holdings().toArray(CorumPort.LifeHolding[]::new))),
            CorumErrorCode.PORTFOLIO_INCOMPLETE);
    }

    @Test
    void validate_allowsOneCentPerRawLine() {
        // 10 raw lines across the pockets: 0.09 of rounding drift is accepted.
        assertThatCode(() -> writer.validate(List.of(
            contractWith("1534.46", 10, holdings().toArray(CorumPort.LifeHolding[]::new)))))
            .doesNotThrowAnyException();
    }

    @Test
    void validate_refusesAEuroFundNotCountedInEuroUnits() {
        CorumPort.LifeHolding euroFund = new CorumPort.LifeHolding("FGCORUM", "CORUM Eurolife",
            new BigDecimal("10"), new BigDecimal("101.576"), new BigDecimal("1015.76"),
            new BigDecimal("1000"), null, true, "euroLife");
        assertRefused(List.of(contractWith("1015.76", 1, euroFund)), CorumErrorCode.INVALID_DATA);
    }

    // ─── write ────────────────────────────────────────────────────────────────

    @Test
    void write_createsOneAssuranceVieAccountWithEveryLineAsAHolding() {
        stubSaves();
        when(memberRepository.getReferenceById(7L)).thenReturn(FamilyMember.builder().id(7L).build());

        var outcomes = writer.write(7L, List.of(contract("1001")), SYNCED_AT);

        assertThat(outcomes).containsEntry("1001", CorumLifeWriter.Outcome.CREATED);
        Account account = savedAccount();
        assertThat(account.getName()).isEqualTo("CORUM Life");
        assertThat(account.getType()).isEqualTo(AccountType.ASSURANCE_VIE);
        assertThat(account.getExternalAccountId()).isEqualTo("corum_life_1001");
        assertThat(account.getProvider()).isEqualTo(CorumLifeWriter.PROVIDER);
        assertThat(account.isManual()).isFalse();
        assertThat(account.getCurrency()).isEqualTo("EUR");
        assertThat(account.getColor()).isEqualTo("#8b5cf6");
        assertThat(account.getCurrentBalance()).isEqualByComparingTo("1534.37");
        // The euro fund is invested money, so it is a holding and the account holds no cash.
        assertThat(account.getCashBalance()).isEqualByComparingTo("0");
        assertThat(account.getLastSyncedAt()).isEqualTo(SYNCED_AT);

        List<AccountHolding> holdings = savedHoldings();
        assertThat(holdings).extracting(AccountHolding::getTicker)
            .containsExactly("CORUM-LIFE-XL", "CORUM-LIFE-FGCORUM");
        AccountHolding xl = holdings.get(0);
        assertThat(xl.getName()).isEqualTo("CORUM XL");
        assertThat(xl.getQuantity()).isEqualByComparingTo("2.65955");
        assertThat(xl.getCurrentPrice()).isEqualByComparingTo("195");
        assertThat(xl.getQuoteCurrency()).isEqualTo("EUR");
        assertThat(xl.getProviderValueEur()).isEqualByComparingTo("518.61");
        assertThat(xl.getProviderPnlEur()).isEqualByComparingTo("38.61");
        assertThat(xl.getAverageBuyIn()).isEqualByComparingTo("180.48166043");
        assertThat(xl.getProviderPriceDate()).isEqualTo(VALUED_ON);
        assertThat(xl.getLastSyncedAt()).isEqualTo(SYNCED_AT);
        AccountHolding euroFund = holdings.get(1);
        assertThat(euroFund.getQuantity()).isEqualByComparingTo("1015.76");
        assertThat(euroFund.getCurrentPrice()).isEqualByComparingTo("1");
        assertThat(euroFund.getProviderValueEur()).isEqualByComparingTo("1015.76");
        assertThat(euroFund.getProviderPriceDate()).isNull();

        verify(holdingRepository).deleteByAccountId(42L);
        // Invested: what was paid into each line, euro fund included.
        verify(accountService).upsertSnapshot(eq(account), eq(new BigDecimal("1534.37")),
            eq(new BigDecimal("1480.00")), eq(LocalDate.now()));
    }

    @Test
    void write_updatesTheLinkedAccountWithoutRenamingIt() {
        Account linked = Account.builder().id(42L).name("My life insurance")
            .type(AccountType.ASSURANCE_VIE).externalAccountId("corum_life_1001")
            .provider(CorumLifeWriter.PROVIDER).isManual(false).currentBalance(BigDecimal.ONE).build();
        when(accountRepository.findByExternalAccountIdAndMemberId("corum_life_1001", 7L))
            .thenReturn(Optional.of(linked));
        stubSaves();

        var outcomes = writer.write(7L, List.of(contract("1001")), SYNCED_AT);

        assertThat(outcomes).containsEntry("1001", CorumLifeWriter.Outcome.UPDATED);
        assertThat(savedAccount()).isSameAs(linked);
        assertThat(linked.getName()).isEqualTo("My life insurance");
        assertThat(linked.getCurrentBalance()).isEqualByComparingTo("1534.37");
        verify(memberRepository, never()).getReferenceById(any());
    }

    @Test
    void write_doesNotBringBackAnAccountTheUserDeleted() {
        when(accountRepository.existsSoftDeletedByExternalAccountIdAndMemberId("corum_life_1001", 7L))
            .thenReturn(true);

        var outcomes = writer.write(7L, List.of(contract("1001")), SYNCED_AT);

        assertThat(outcomes).containsEntry("1001", CorumLifeWriter.Outcome.IGNORED);
        verify(accountRepository, never()).save(any());
        verify(holdingRepository, never()).deleteByAccountId(any());
        verify(accountService, never()).upsertSnapshot(any(), any(), any(), any());
        verify(operationWriter, never()).reconcile(any(), any(), any(), any());
    }

    @Test
    void write_adoptsTheOneManualCorumLifeAccountWithoutHoldings() {
        Account manual = manualCorumLife(42L, "Corum-life");
        when(accountRepository.findAllByMemberIdOrderByCreatedAtAsc(7L)).thenReturn(List.of(manual));
        when(holdingRepository.findByAccount_Id(42L)).thenReturn(List.of());
        stubSaves();

        var outcomes = writer.write(7L, List.of(contract("1001")), SYNCED_AT);

        assertThat(outcomes).containsEntry("1001", CorumLifeWriter.Outcome.LINKED);
        assertThat(savedAccount()).isSameAs(manual);
        assertThat(manual.getName()).isEqualTo("Corum-life");
        assertThat(manual.getExternalAccountId()).isEqualTo("corum_life_1001");
        assertThat(manual.isManual()).isFalse();
        assertThat(manual.getProvider()).isEqualTo(CorumLifeWriter.PROVIDER);
    }

    /** A manual account filed under CORUM is the same contract whatever the user named it. */
    @Test
    void write_adoptsTheOneManualAccountFiledUnderCorumWhateverItsName() {
        Account manual = manualCorumLife(42L, "Mon assurance-vie");
        manual.setProvider("corum");
        when(accountRepository.findAllByMemberIdOrderByCreatedAtAsc(7L)).thenReturn(List.of(
            manual, manualCorumLife(43L, "Linxea Spirit")));
        when(holdingRepository.findByAccount_Id(42L)).thenReturn(List.of());
        stubSaves();

        var outcomes = writer.write(7L, List.of(contract("1001")), SYNCED_AT);

        assertThat(outcomes).containsEntry("1001", CorumLifeWriter.Outcome.LINKED);
        assertThat(savedAccount()).isSameAs(manual);
        assertThat(manual.getName()).isEqualTo("Mon assurance-vie");
        assertThat(manual.getExternalAccountId()).isEqualTo("corum_life_1001");
        verify(memberRepository, never()).getReferenceById(any());
    }

    @Test
    void write_writesNothingWhenANamedAndAProviderMatchBothStandForTheContract() {
        Account byProvider = manualCorumLife(41L, "Mon assurance-vie");
        byProvider.setProvider("CORUM");
        when(accountRepository.findAllByMemberIdOrderByCreatedAtAsc(7L)).thenReturn(List.of(
            manualCorumLife(40L, "CORUM Life"), byProvider));

        var outcomes = writer.write(7L, List.of(contract("1001")), SYNCED_AT);

        assertThat(outcomes).containsEntry("1001", CorumLifeWriter.Outcome.AMBIGUOUS);
        assertNothingWritten();
    }

    @Test
    void write_neverAdoptsAProviderMatchAlreadyLinkedOrNotAnAssuranceVie() {
        Account linkedElsewhere = manualCorumLife(41L, "Mon assurance-vie");
        linkedElsewhere.setProvider("CORUM");
        linkedElsewhere.setExternalAccountId("finary_123");
        Account scpi = manualCorumLife(42L, "Mes SCPI");
        scpi.setProvider("CORUM");
        scpi.setType(AccountType.SCPI);
        when(accountRepository.findAllByMemberIdOrderByCreatedAtAsc(7L)).thenReturn(List.of(linkedElsewhere, scpi));
        when(memberRepository.getReferenceById(7L)).thenReturn(FamilyMember.builder().id(7L).build());
        stubSaves();

        var outcomes = writer.write(7L, List.of(contract("1001")), SYNCED_AT);

        assertThat(outcomes).containsEntry("1001", CorumLifeWriter.Outcome.CREATED);
        verify(holdingRepository, never()).findByAccount_Id(any());
    }

    /**
     * Adopting would replace holdings the user typed by hand, which no sync can undo; and a
     * new account next to the manual one would count the contract twice in the net worth.
     */
    @Test
    void write_writesNothingWhenTheManualAccountHasHoldings() {
        Account manual = manualCorumLife(41L, "CORUM Life");
        when(accountRepository.findAllByMemberIdOrderByCreatedAtAsc(7L)).thenReturn(List.of(manual));
        when(holdingRepository.findByAccount_Id(41L))
            .thenReturn(List.of(AccountHolding.builder().ticker("XL").quantity(BigDecimal.ONE).build()));

        var outcomes = writer.write(7L, List.of(contract("1001")), SYNCED_AT);

        assertThat(outcomes).containsEntry("1001", CorumLifeWriter.Outcome.AMBIGUOUS);
        assertThat(manual.getExternalAccountId()).isNull();
        assertNothingWritten();
    }

    @Test
    void write_writesNothingWhenSeveralManualAccountsMatch() {
        when(accountRepository.findAllByMemberIdOrderByCreatedAtAsc(7L)).thenReturn(List.of(
            manualCorumLife(40L, "CORUM Life"), manualCorumLife(41L, "corum life")));

        var outcomes = writer.write(7L, List.of(contract("1001")), SYNCED_AT);

        assertThat(outcomes).containsEntry("1001", CorumLifeWriter.Outcome.AMBIGUOUS);
        verify(holdingRepository, never()).findByAccount_Id(any());
        assertNothingWritten();
    }

    /** One manual account for several contracts: which one it stands for is the user's call. */
    @Test
    void write_severalContractsWriteNothingNextToAManualAccount() {
        when(accountRepository.findAllByMemberIdOrderByCreatedAtAsc(7L))
            .thenReturn(List.of(manualCorumLife(40L, "CORUM Life")));

        var outcomes = writer.write(7L, List.of(contract("1001"), contract("1002")), SYNCED_AT);

        assertThat(outcomes).containsOnlyKeys("1001", "1002")
            .containsValues(CorumLifeWriter.Outcome.AMBIGUOUS)
            .doesNotContainValue(CorumLifeWriter.Outcome.CREATED);
        assertNothingWritten();
    }

    @Test
    void write_severalContractsAdoptNothingAndCarryTheirCodeInTheName() {
        when(memberRepository.getReferenceById(7L)).thenReturn(FamilyMember.builder().id(7L).build());
        stubSaves();

        var outcomes = writer.write(7L, List.of(contract("1001"), contract("1002")), SYNCED_AT);

        assertThat(outcomes).containsOnlyKeys("1001", "1002")
            .containsValues(CorumLifeWriter.Outcome.CREATED);
        ArgumentCaptor<Account> saved = ArgumentCaptor.forClass(Account.class);
        verify(accountRepository, org.mockito.Mockito.times(2)).save(saved.capture());
        assertThat(saved.getAllValues()).extracting(Account::getName)
            .containsExactly("CORUM Life 1001", "CORUM Life 1002");
    }

    @Test
    void write_letsAccountServiceDeriveTheCostWhenALineHasNoPaidInAmount() {
        when(memberRepository.getReferenceById(7L)).thenReturn(FamilyMember.builder().id(7L).build());
        stubSaves();
        CorumPort.LifeContract contract = contractWith("518.61", 1,
            new CorumPort.LifeHolding("XL", "CORUM XL", new BigDecimal("2.65955"), new BigDecimal("195"),
                new BigDecimal("518.61"), null, VALUED_ON, false, "realEstate"));

        writer.write(7L, List.of(contract), SYNCED_AT);

        AccountHolding xl = savedHoldings().get(0);
        assertThat(xl.getAverageBuyIn()).isNull();
        assertThat(xl.getProviderPnlEur()).isNull();
        verify(accountService).upsertSnapshot(any(Account.class), eq(new BigDecimal("518.61")), eq(LocalDate.now()));
        verify(accountService, never()).upsertSnapshot(any(), any(), any(), any());
    }

    @Test
    void validate_refusesANegativeContractCost() {
        assertRefused(List.of(contractWith("1534.37", 3, "-1", holdings().toArray(CorumPort.LifeHolding[]::new))),
            CorumErrorCode.INVALID_DATA);
    }

    @Test
    void write_storesTheContractFacts() {
        stubSaves();
        when(memberRepository.getReferenceById(7L)).thenReturn(FamilyMember.builder().id(7L).build());

        writer.write(7L, List.of(contract("1001")), SYNCED_AT);

        Account account = savedAccount();
        assertThat(account.getProviderInvestedEur()).isEqualByComparingTo("1480.00");
        assertThat(account.getProviderPaidInEur()).isEqualByComparingTo("1500");
        assertThat(account.getScheduledPaymentEur()).isEqualByComparingTo("50");
        assertThat(account.getScheduledPaymentCadence()).isEqualTo(RecurringCadence.MONTHLY);
        assertThat(account.getOpenedAt()).isEqualTo(OPENED_ON);
    }

    @Test
    void write_keepsAnOpeningDateTheMemberTyped() {
        LocalDate typed = LocalDate.of(2023, 12, 20);
        Account linked = Account.builder().id(42L).name("CORUM Life").type(AccountType.ASSURANCE_VIE)
            .externalAccountId("corum_life_1001").provider(CorumLifeWriter.PROVIDER).isManual(false)
            .openedAt(typed).build();
        when(accountRepository.findByExternalAccountIdAndMemberId("corum_life_1001", 7L))
            .thenReturn(Optional.of(linked));
        stubSaves();

        writer.write(7L, List.of(contract("1001")), SYNCED_AT);

        assertThat(linked.getOpenedAt()).isEqualTo(typed);
    }

    /**
     * A switch out of the euro fund leaves the lines' costs short of the contract's: the line
     * switched into would show a gain it never made, so no line carries a cost, and the account
     * and its snapshot take the contract's own figure.
     */
    @Test
    void write_dropsLineCostsThatDoNotAddUpToTheContract() {
        when(memberRepository.getReferenceById(7L)).thenReturn(FamilyMember.builder().id(7L).build());
        stubSaves();
        // Lines: 480 + 1000 = 1480; the contract says 1580 (100 switched, left out of the lines).
        CorumPort.LifeContract contract = contractWith("1534.37", 3, "1580.00",
            holdings().toArray(CorumPort.LifeHolding[]::new));

        writer.write(7L, List.of(contract), SYNCED_AT);

        assertThat(savedAccount().getProviderInvestedEur()).isEqualByComparingTo("1580.00");
        assertThat(savedHoldings()).allSatisfy(holding -> {
            assertThat(holding.getAverageBuyIn()).isNull();
            assertThat(holding.getProviderPnlEur()).isNull();
            assertThat(holding.getProviderValueEur()).isNotNull();
        });
        verify(accountService).upsertSnapshot(any(Account.class), eq(new BigDecimal("1534.37")),
            eq(new BigDecimal("1580.00")), eq(LocalDate.now()));
    }

    @Test
    void write_keepsLineCostsWithinTheLineAllowance() {
        when(memberRepository.getReferenceById(7L)).thenReturn(FamilyMember.builder().id(7L).build());
        stubSaves();
        // 3 raw lines: 0.05 of drift between the lines' costs and the contract's is allowed.
        CorumPort.LifeContract contract = contractWith("1534.37", 3, "1480.05",
            holdings().toArray(CorumPort.LifeHolding[]::new));

        writer.write(7L, List.of(contract), SYNCED_AT);

        assertThat(savedHoldings().get(0).getProviderPnlEur()).isEqualByComparingTo("38.61");
        verify(accountService).upsertSnapshot(any(Account.class), eq(new BigDecimal("1534.37")),
            eq(new BigDecimal("1480.05")), eq(LocalDate.now()));
    }

    /** A stale sidecar sends no contract cost: the line costs are written as before. */
    @Test
    void write_withoutAContractCostKeepsTheLineCosts() {
        when(memberRepository.getReferenceById(7L)).thenReturn(FamilyMember.builder().id(7L).build());
        stubSaves();

        writer.write(7L, List.of(contractWith("1534.37", 3, holdings().toArray(CorumPort.LifeHolding[]::new))),
            SYNCED_AT);

        Account account = savedAccount();
        assertThat(account.getProviderInvestedEur()).isNull();
        assertThat(account.getProviderPaidInEur()).isNull();
        assertThat(account.getScheduledPaymentEur()).isNull();
        assertThat(account.getScheduledPaymentCadence()).isNull();
        assertThat(account.getOpenedAt()).isNull();
        assertThat(savedHoldings().get(0).getAverageBuyIn()).isEqualByComparingTo("180.48166043");
        verify(accountService).upsertSnapshot(any(Account.class), eq(new BigDecimal("1534.37")),
            eq(new BigDecimal("1480.00")), eq(LocalDate.now()));
    }

    @Test
    void write_aContractCostStillDropsLineCostsWhenALineHasNone() {
        when(memberRepository.getReferenceById(7L)).thenReturn(FamilyMember.builder().id(7L).build());
        stubSaves();
        CorumPort.LifeContract contract = contractWith("518.61", 1, "500",
            new CorumPort.LifeHolding("XL", "CORUM XL", new BigDecimal("2.65955"), new BigDecimal("195"),
                new BigDecimal("518.61"), null, VALUED_ON, false, "realEstate"));

        writer.write(7L, List.of(contract), SYNCED_AT);

        assertThat(savedHoldings().get(0).getAverageBuyIn()).isNull();
        verify(accountService).upsertSnapshot(any(Account.class), eq(new BigDecimal("518.61")),
            eq(new BigDecimal("500")), eq(LocalDate.now()));
    }

    @Test
    void write_givesRealEstateLinesTheirTierAndLeavesTheRestToTheAccount() {
        when(memberRepository.getReferenceById(7L)).thenReturn(FamilyMember.builder().id(7L).build());
        stubSaves();
        CorumPort.LifeContract contract = contractWith("1534.37", 4,
            unitLinked("XL", "2.65955", "518.61", "480"),
            new CorumPort.LifeHolding("CBEHY", "CORUM BEHY", BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO,
                BigDecimal.ZERO, VALUED_ON, false, "bondFund"),
            new CorumPort.LifeHolding("NEW", "CORUM NEW", BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO,
                BigDecimal.ZERO, VALUED_ON, false, null),
            new CorumPort.LifeHolding("FGCORUM", "CORUM EuroLife (fonds euros)", new BigDecimal("1015.76"),
                BigDecimal.ONE, new BigDecimal("1015.76"), new BigDecimal("1000.00"), null, true, "euroLife"));

        writer.write(7L, List.of(contract), SYNCED_AT);

        assertThat(savedHoldings()).extracting(AccountHolding::getProviderWealthTier)
            .containsExactly(WealthTier.REAL_ESTATE, null, null, null);
    }

    @Test
    void providerTier_onlyRealEstateIsConclusive() {
        assertThat(CorumLifeWriter.providerTier("realEstate")).isEqualTo(WealthTier.REAL_ESTATE);
        assertThat(CorumLifeWriter.providerTier("bondFund")).isNull();
        assertThat(CorumLifeWriter.providerTier("euroLife")).isNull();
        assertThat(CorumLifeWriter.providerTier("privateEquity")).isNull();
        assertThat(CorumLifeWriter.providerTier(null)).isNull();
    }

    @Test
    void cadenceOf_mapsCorumFrequencies() {
        assertThat(CorumLifeWriter.cadenceOf(plan("50", "monthly"))).isEqualTo(RecurringCadence.MONTHLY);
        assertThat(CorumLifeWriter.cadenceOf(plan("50", "Monthly"))).isEqualTo(RecurringCadence.MONTHLY);
        assertThat(CorumLifeWriter.cadenceOf(plan("50", "quarterly"))).isEqualTo(RecurringCadence.QUARTERLY);
        assertThat(CorumLifeWriter.cadenceOf(plan("50", "annual"))).isEqualTo(RecurringCadence.YEARLY);
        assertThat(CorumLifeWriter.cadenceOf(plan("50", "yearly"))).isEqualTo(RecurringCadence.YEARLY);
        assertThat(CorumLifeWriter.cadenceOf(plan("50", "fortnightly"))).isNull();
        assertThat(CorumLifeWriter.cadenceOf(plan("0", "monthly"))).isNull();
        assertThat(CorumLifeWriter.cadenceOf(null)).isNull();
    }

    /** An unknown frequency or a zero plan leaves the plan out; the contract is still written. */
    @Test
    void write_leavesOutAPlanItCannotRead() {
        for (CorumPort.ScheduledPayment payment : List.of(plan("50", "weekly-ish"), plan("0", "monthly"))) {
            Account linked = Account.builder().id(42L).name("CORUM Life").type(AccountType.ASSURANCE_VIE)
                .externalAccountId("corum_life_1001").provider(CorumLifeWriter.PROVIDER).isManual(false)
                .scheduledPaymentEur(new BigDecimal("80")).scheduledPaymentCadence(RecurringCadence.MONTHLY)
                .build();
            when(accountRepository.findByExternalAccountIdAndMemberId("corum_life_1001", 7L))
                .thenReturn(Optional.of(linked));
            stubSaves();

            var outcomes = writer.write(7L, List.of(contractPaying(payment)), SYNCED_AT);

            assertThat(outcomes).containsEntry("1001", CorumLifeWriter.Outcome.UPDATED);
            assertThat(linked.getScheduledPaymentEur()).isNull();
            assertThat(linked.getScheduledPaymentCadence()).isNull();
            assertThat(linked.getCurrentBalance()).isEqualByComparingTo("1534.37");
        }
    }

    private static CorumPort.ScheduledPayment plan(String amount, String frequency) {
        return new CorumPort.ScheduledPayment(new BigDecimal(amount), frequency);
    }

    // ─── helpers ──────────────────────────────────────────────────────────────

    private void assertRefused(List<CorumPort.LifeContract> contracts, CorumErrorCode code) {
        assertThatThrownBy(() -> writer.validate(contracts))
            .isInstanceOfSatisfying(SyncException.class, error ->
                assertThat(error.getCode()).isEqualTo(code.name()));
    }

    private void stubSaves() {
        lenient().when(accountRepository.save(any(Account.class))).thenAnswer(inv -> {
            Account account = inv.getArgument(0);
            if (account.getId() == null) {
                account.setId(42L);
            }
            return account;
        });
    }

    private Account savedAccount() {
        ArgumentCaptor<Account> saved = ArgumentCaptor.forClass(Account.class);
        verify(accountRepository).save(saved.capture());
        return saved.getValue();
    }

    // ─── operations ───────────────────────────────────────────────────────────

    @Test
    void validate_acceptsAContractWhoseOperationsWereNotRead() {
        assertThatCode(() -> writer.validate(List.of(withOperations(null)))).doesNotThrowAnyException();
    }

    @Test
    void validate_acceptsTheTwoLegsOfOneSwitchUnderOneId() {
        assertThatCode(() -> writer.validate(List.of(withOperations(List.of(
            operation("7", "arbitrage", "2026-06-15", "-100"),
            operation("7", "arbitrage", "2026-06-15", "100"))))))
            .doesNotThrowAnyException();
    }

    /** The valuation still syncs; only that contract's operation list is dropped. */
    @Test
    void validate_acceptsTwoOperationsThatStillShareAKey() {
        assertThatCode(() -> writer.validate(List.of(withOperations(List.of(
            operation("7", "arbitrage", "2026-06-15", "100"),
            operation("7", "arbitrage", "2026-06-16", "100"))))))
            .doesNotThrowAnyException();
    }

    @Test
    void write_keepsTheStoredOperationsWhenTwoStillShareAKey() {
        stubSaves();
        when(memberRepository.getReferenceById(7L)).thenReturn(FamilyMember.builder().id(7L).build());
        CorumPort.LifeContract contract = withOperations(List.of(
            operation("7", "arbitrage", "2026-06-15", "100"),
            operation("7", "arbitrage", "2026-06-16", "100")));

        var outcomes = writer.write(7L, List.of(contract), SYNCED_AT);

        assertThat(outcomes).containsEntry("1001", CorumLifeWriter.Outcome.CREATED);
        Account account = savedAccount();
        assertThat(account.getCurrentBalance()).isEqualByComparingTo("1534.37");
        verify(operationWriter).reconcile(eq(7L), eq(account), isNull(), eq(contract.holdings()));
    }

    @Test
    void validate_refusesAnOperationWithoutADateIdTypeOrAmount() {
        for (CorumPort.LifeOperation incomplete : List.of(
            new CorumPort.LifeOperation("7", "arbitrage", null, null, BigDecimal.ONE, null),
            new CorumPort.LifeOperation(" ", "arbitrage", null, VALUED_ON, BigDecimal.ONE, null),
            new CorumPort.LifeOperation("7", null, null, VALUED_ON, BigDecimal.ONE, null),
            new CorumPort.LifeOperation("7", "arbitrage", null, VALUED_ON, null, null))) {
            assertRefused(List.of(withOperations(List.of(incomplete))), CorumErrorCode.INVALID_DATA);
        }
        List<CorumPort.LifeOperation> withNull = new ArrayList<>();
        withNull.add(null);
        assertRefused(List.of(withOperations(withNull)), CorumErrorCode.INVALID_DATA);
    }

    @Test
    void validate_refusesAnOperationTypeTooLongToStore() {
        assertRefused(List.of(withOperations(List.of(
            operation("7", "x".repeat(61), "2026-06-15", "1")))), CorumErrorCode.INVALID_DATA);
    }

    @Test
    void write_reconcilesTheOperationsOfTheWrittenAccount() {
        stubSaves();
        when(memberRepository.getReferenceById(7L)).thenReturn(FamilyMember.builder().id(7L).build());
        List<CorumPort.LifeOperation> operations = List.of(operation("7", "scheduledPayment", "2026-10-05", "50"));
        CorumPort.LifeContract contract = withOperations(operations);

        writer.write(7L, List.of(contract), SYNCED_AT);

        Account account = savedAccount();
        verify(operationWriter).reconcile(eq(7L), eq(account), eq(operations), eq(contract.holdings()));
    }

    private static CorumPort.LifeContract withOperations(List<CorumPort.LifeOperation> operations) {
        return new CorumPort.LifeContract("1001", "EUR", new BigDecimal("1534.37"), VALUED_ON, 3,
            new BigDecimal("1480.00"), null, null, null, true, holdings(), operations);
    }

    private static CorumPort.LifeOperation operation(String id, String type, String date, String amount) {
        return new CorumPort.LifeOperation(id, type, type, LocalDate.parse(date), new BigDecimal(amount), null);
    }

    @SuppressWarnings("unchecked")
    private List<AccountHolding> savedHoldings() {
        ArgumentCaptor<List<AccountHolding>> saved = ArgumentCaptor.forClass(List.class);
        verify(holdingRepository).saveAll(saved.capture());
        return new ArrayList<>(saved.getValue());
    }

    private static Account manualCorumLife(Long id, String name) {
        return Account.builder().id(id).name(name).type(AccountType.ASSURANCE_VIE)
            .currency("EUR").currentBalance(new BigDecimal("1000")).build();
    }

    private static CorumPort.LifeContract contract(String code) {
        return new CorumPort.LifeContract(code, "EUR", new BigDecimal("1534.37"), VALUED_ON, 3,
            new BigDecimal("1480.00"), new BigDecimal("1500"), OPENED_ON,
            new CorumPort.ScheduledPayment(new BigDecimal("50"), "monthly"), true, holdings(), null);
    }

    /** A sidecar that predates the contract-level figures: they all come back null. */
    private static CorumPort.LifeContract contractWith(String total, int lines, CorumPort.LifeHolding... holdings) {
        return contractWith(total, lines, null, holdings);
    }

    private static CorumPort.LifeContract contractWith(
        String total, int lines, String netInvested, CorumPort.LifeHolding... holdings
    ) {
        return new CorumPort.LifeContract("1001", "EUR", new BigDecimal(total), VALUED_ON, lines,
            netInvested == null ? null : new BigDecimal(netInvested), null, null, null, true, List.of(holdings), null);
    }

    private static CorumPort.LifeContract contractPaying(CorumPort.ScheduledPayment payment) {
        return new CorumPort.LifeContract("1001", "EUR", new BigDecimal("1534.37"), VALUED_ON, 3,
            new BigDecimal("1480.00"), null, null, payment, true, holdings(), null);
    }

    /** CORUM XL across two pockets (2 raw lines) and the euro fund (1 raw line). */
    private static List<CorumPort.LifeHolding> holdings() {
        return List.of(
            unitLinked("XL", "2.65955", "518.61", "480"),
            new CorumPort.LifeHolding("FGCORUM", "CORUM EuroLife (fonds euros)", new BigDecimal("1015.76"),
                BigDecimal.ONE, new BigDecimal("1015.76"), new BigDecimal("1000.00"), null, true, "euroLife")
        );
    }

    private static CorumPort.LifeHolding unitLinked(String code, String quantity, String value, String invested) {
        return new CorumPort.LifeHolding(code, "CORUM " + code, new BigDecimal(quantity), new BigDecimal("195"),
            new BigDecimal(value), new BigDecimal(invested), VALUED_ON, false, "realEstate");
    }

    private void assertNothingWritten() {
        verify(accountRepository, never()).save(any());
        verify(holdingRepository, never()).deleteByAccountId(any());
        verify(holdingRepository, never()).saveAll(any());
        verify(accountService, never()).upsertSnapshot(any(), any(), any(), any());
        verify(accountService, never()).upsertSnapshot(any(), any(), any());
        verify(operationWriter, never()).reconcile(any(), any(), any(), any());
    }
}
