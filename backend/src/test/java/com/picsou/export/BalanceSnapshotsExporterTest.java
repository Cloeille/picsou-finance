package com.picsou.export;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.picsou.model.Account;
import com.picsou.model.AccountType;
import com.picsou.model.AppUser;
import com.picsou.model.BalanceSnapshot;
import com.picsou.model.FamilyMember;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.BalanceSnapshotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Plan 005 part B: {@link BalanceSnapshotsExporter} must stream per account instead of
 * collecting the member's entire snapshot history before writing (see the class Javadoc
 * on the exporter and docs/features/data-export.md). This test drives the exporter
 * directly (bypassing {@link DataExportService}) so per-pass repository call counts can
 * be asserted precisely.
 */
@ExtendWith(MockitoExtension.class)
class BalanceSnapshotsExporterTest {

    @Mock private AccountRepository accountRepository;
    @Mock private BalanceSnapshotRepository balanceSnapshotRepository;

    private BalanceSnapshotsExporter exporter;
    private AppUser user;
    private Account accountA;
    private Account accountB;

    @BeforeEach
    void setUp() {
        FamilyMember member = FamilyMember.builder().id(42L).displayName("Chloé Test").build();
        user = AppUser.builder().id(7L).username("chloe").member(member).build();
        exporter = new BalanceSnapshotsExporter(accountRepository, balanceSnapshotRepository);

        accountA = Account.builder().id(100L).member(member).name("Livret A").type(AccountType.SAVINGS).build();
        accountB = Account.builder().id(200L).member(member).name("Compte-titres").type(AccountType.COMPTE_TITRES).build();
    }

    @Test
    void streamsPerAccount_inAccountThenDateOrder_withOneRepositoryCallPerAccountPerPass() throws Exception {
        when(accountRepository.findAllByMemberIdOrderByCreatedAtAsc(42L))
            .thenReturn(List.of(accountA, accountB));

        List<BalanceSnapshot> snapshotsA = List.of(
            BalanceSnapshot.builder().id(1L).account(accountA).date(LocalDate.of(2026, 1, 1)).balance(new BigDecimal("1000")).investedAmount(new BigDecimal("900")).build(),
            BalanceSnapshot.builder().id(2L).account(accountA).date(LocalDate.of(2026, 1, 2)).balance(new BigDecimal("1010")).investedAmount(new BigDecimal("900")).build(),
            BalanceSnapshot.builder().id(3L).account(accountA).date(LocalDate.of(2026, 1, 3)).balance(new BigDecimal("1020")).investedAmount(new BigDecimal("900")).build()
        );
        List<BalanceSnapshot> snapshotsB = List.of(
            BalanceSnapshot.builder().id(4L).account(accountB).date(LocalDate.of(2026, 1, 1)).balance(new BigDecimal("5000")).investedAmount(new BigDecimal("4800")).build(),
            BalanceSnapshot.builder().id(5L).account(accountB).date(LocalDate.of(2026, 1, 2)).balance(new BigDecimal("5050")).investedAmount(new BigDecimal("4800")).build(),
            BalanceSnapshot.builder().id(6L).account(accountB).date(LocalDate.of(2026, 1, 3)).balance(new BigDecimal("5100")).investedAmount(new BigDecimal("4800")).build()
        );
        when(balanceSnapshotRepository.findByAccountIdOrderByDateAsc(100L)).thenReturn(snapshotsA);
        when(balanceSnapshotRepository.findByAccountIdOrderByDateAsc(200L)).thenReturn(snapshotsB);

        ExportContext ctx = new ExportContext(true);

        // --- CSV pass ---
        ByteArrayOutputStream csvOut = new ByteArrayOutputStream();
        CsvWriter csv = new CsvWriter(csvOut);
        exporter.writeCsv(user, ctx, csv);
        csv.flush();
        List<String> csvLines = Arrays.stream(csvOut.toString(StandardCharsets.UTF_8).split("\r\n"))
            .filter(line -> !line.isBlank())
            .toList();
        assertThat(csvLines).hasSize(6);
        assertThat(csvLines.get(0)).contains("1,100,2026-01-01,1000");
        assertThat(csvLines.get(1)).contains("2,100,2026-01-02,1010");
        assertThat(csvLines.get(2)).contains("3,100,2026-01-03,1020");
        assertThat(csvLines.get(3)).contains("4,200,2026-01-01,5000");
        assertThat(csvLines.get(4)).contains("5,200,2026-01-02,5050");
        assertThat(csvLines.get(5)).contains("6,200,2026-01-03,5100");

        // --- JSON pass ---
        ByteArrayOutputStream jsonOut = new ByteArrayOutputStream();
        JsonFactory factory = new JsonFactory();
        try (JsonGenerator json = factory.createGenerator(jsonOut)) {
            exporter.writeJson(user, ctx, json);
        }
        JsonNode node = new ObjectMapper().readTree(jsonOut.toByteArray());
        assertThat(node).hasSize(6);
        assertThat(node.get(0).get("account_id").asLong()).isEqualTo(100L);
        assertThat(node.get(0).get("date").asText()).isEqualTo("2026-01-01");
        assertThat(node.get(2).get("account_id").asLong()).isEqualTo(100L);
        assertThat(node.get(2).get("date").asText()).isEqualTo("2026-01-03");
        assertThat(node.get(3).get("account_id").asLong()).isEqualTo(200L);
        assertThat(node.get(3).get("date").asText()).isEqualTo("2026-01-01");
        assertThat(node.get(5).get("account_id").asLong()).isEqualTo(200L);
        assertThat(node.get(5).get("date").asText()).isEqualTo("2026-01-03");

        // Exactly one call per account per pass (CSV + JSON = 2 passes) — never a
        // whole-member collection call, and no third/extra call sneaking in.
        verify(balanceSnapshotRepository, times(2)).findByAccountIdOrderByDateAsc(100L);
        verify(balanceSnapshotRepository, times(2)).findByAccountIdOrderByDateAsc(200L);
        verifyNoMoreInteractions(balanceSnapshotRepository);
        verify(accountRepository, times(2)).findAllByMemberIdOrderByCreatedAtAsc(42L);
    }
}
