package com.picsou.service;

import com.picsou.dto.FinaryAccountMapping;
import com.picsou.dto.FinaryAccountPreview;
import com.picsou.dto.FinaryImportRequest;
import com.picsou.dto.FinaryMappingAction;
import com.picsou.dto.FinaryPreviewResponse;
import com.picsou.dto.NewAccountDetails;
import com.picsou.finary.FinaryAccountMatcher;
import com.picsou.finary.FinaryPersistenceHelper;
import com.picsou.model.Account;
import com.picsou.model.AccountType;
import com.picsou.model.FamilyMember;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.BalanceSnapshotRepository;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.TransactionRepository;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FinaryImportServiceTest {

    @Mock AccountRepository accountRepository;
    @Mock BalanceSnapshotRepository balanceSnapshotRepository;
    @Mock TransactionRepository transactionRepository;
    @Mock FamilyMemberRepository familyMemberRepository;
    @Mock FinaryPersistenceHelper persistenceHelper;

    @InjectMocks FinaryImportService service;

    @Test
    void executeImport_takesTheFinaryBalanceOnAMappedAccount() throws Exception {
        Account existing = Account.builder()
            .name("Compte courant").type(AccountType.CHECKING)
            .currency("EUR").currentBalance(new BigDecimal("100")).build();
        existing.setId(7L);
        when(accountRepository.findAllByMemberIdOrderByCreatedAtAsc(1L)).thenReturn(List.of(existing));
        when(accountRepository.findByIdAndMemberId(7L, 1L)).thenReturn(Optional.of(existing));
        when(familyMemberRepository.findById(1L)).thenReturn(Optional.of(FamilyMember.builder().id(1L).build()));
        when(accountRepository.save(any(Account.class))).thenAnswer(inv -> inv.getArgument(0));

        FinaryPreviewResponse preview = service.preview(xlsxWithChecking("Compte courant", 250, "USD"), 1L);
        assertThat(preview.suggestedMappings()).singleElement()
            .satisfies(m -> assertThat(m.action()).isEqualTo(FinaryMappingAction.MAP_EXISTING));

        service.executeImport(new FinaryImportRequest(preview.suggestedMappings(), preview.fileToken()), 1L);

        verify(accountRepository).save(existing);
        assertThat(existing.getCurrentBalance()).isEqualByComparingTo("250");
        assertThat(existing.getCurrency()).isEqualTo("USD");
    }

    @Test
    void executeImport_rejectsTwoRowsMappedOntoOneAccount_beforeChangingAnything() throws Exception {
        when(accountRepository.findAllByMemberIdOrderByCreatedAtAsc(1L)).thenReturn(List.of());
        when(familyMemberRepository.findById(1L)).thenReturn(Optional.of(FamilyMember.builder().id(1L).build()));
        FinaryPreviewResponse preview = service.preview(xlsxWithCheckings("Joint", "Perso"), 1L);
        List<FinaryAccountMapping> clash = preview.accounts().stream()
            .map(row -> new FinaryAccountMapping(row.finaryId(), row.finaryName(), row.finaryCategory(),
                FinaryMappingAction.MAP_EXISTING, 7L, null))
            .toList();

        assertThatThrownBy(() -> service.executeImport(new FinaryImportRequest(clash, preview.fileToken()), 1L))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("7");
        verify(accountRepository, never()).findByIdAndMemberId(any(), any());
        verify(accountRepository, never()).save(any());
        verify(persistenceHelper, never()).importTransactions(any(), any(), any());
    }

    @Test
    void anAccountCreatedByTheXlsxImport_isFoundByTheApiSyncThroughItsSlug() throws Exception {
        when(accountRepository.findAllByMemberIdOrderByCreatedAtAsc(1L)).thenReturn(List.of());
        when(familyMemberRepository.findById(1L)).thenReturn(Optional.of(FamilyMember.builder().id(1L).build()));
        when(accountRepository.save(any(Account.class))).thenAnswer(inv -> {
            Account saved = inv.getArgument(0);
            saved.setId(7L);
            return saved;
        });
        FinaryPreviewResponse preview = service.preview(xlsxWithCheckings("Checking"), 1L);
        FinaryAccountPreview row = preview.accounts().get(0);
        List<FinaryAccountMapping> create = List.of(new FinaryAccountMapping(
            row.finaryId(), row.finaryName(), row.finaryCategory(), FinaryMappingAction.CREATE_NEW, null,
            new NewAccountDetails("Household", AccountType.CHECKING, "Bank", "EUR", null)));
        service.executeImport(new FinaryImportRequest(create, preview.fileToken()), 1L);
        ArgumentCaptor<Account> created = ArgumentCaptor.forClass(Account.class);
        verify(accountRepository).save(created.capture());

        // The same account seen by the API: a numeric id, its category as a URL segment.
        List<Optional<FinaryAccountMatcher.Suggestion>> hits = FinaryAccountMatcher.suggestAll(
            List.of(new FinaryAccountMatcher.FinaryRow("4242", "Checking", "checkings", "checking")),
            List.of(created.getValue()));

        assertThat(hits.get(0)).hasValueSatisfying(hit -> {
            assertThat(hit.account().getId()).isEqualTo(7L);
            assertThat(hit.reason()).isEqualTo("legacySlug");
        });
    }

    private static MockMultipartFile xlsxWithCheckings(String... names) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Checkings");
            sheet.createRow(0).createCell(0).setCellValue("name");
            for (int i = 0; i < names.length; i++) {
                Row row = sheet.createRow(i + 1);
                row.createCell(0).setCellValue(names[i]);
                row.createCell(1).setCellValue("Bank");
                row.createCell(2).setCellValue(100);
                row.createCell(3).setCellValue("EUR");
            }
            workbook.write(out);
            return new MockMultipartFile("file", "finary.xlsx", null, out.toByteArray());
        }
    }

    private static MockMultipartFile xlsxWithChecking(String name, double balance, String currency) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Checkings");
            sheet.createRow(0).createCell(0).setCellValue("name");
            Row row = sheet.createRow(1);
            row.createCell(0).setCellValue(name);
            row.createCell(1).setCellValue("Bank");
            row.createCell(2).setCellValue(balance);
            row.createCell(3).setCellValue(currency);
            workbook.write(out);
            return new MockMultipartFile("file", "finary.xlsx", null, out.toByteArray());
        }
    }
}
