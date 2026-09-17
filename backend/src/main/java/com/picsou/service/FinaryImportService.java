package com.picsou.service;

import com.picsou.dto.*;
import com.picsou.finary.FinaryAccountMatcher;
import com.picsou.finary.FinaryPersistenceHelper;
import com.picsou.model.*;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.BalanceSnapshotRepository;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.TransactionRepository;
import com.picsou.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class FinaryImportService {

    private static final Logger log = LoggerFactory.getLogger(FinaryImportService.class);
    private static final DateTimeFormatter DATE_PARSER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final List<String> KNOWN_CATEGORIES = List.of(
        "Checkings", "Savings", "Investments", "Real Estate", "Cryptos",
        "Fonds Euro", "Commodities", "Credits", "Other Assets", "Startups"
    );
    private static final List<String> TRANSACTION_CATEGORIES = List.of(
        "Checkings", "Savings", "Investments", "Credits"
    );

    private final AccountRepository accountRepository;
    private final BalanceSnapshotRepository balanceSnapshotRepository;
    private final TransactionRepository transactionRepository;
    private final FamilyMemberRepository familyMemberRepository;
    private final FinaryPersistenceHelper persistenceHelper;
    private final ConcurrentHashMap<String, ParsedFinaryData> cache = new ConcurrentHashMap<>();

    record ParsedFinaryData(
        List<FinaryPersistenceHelper.ParsedFinaryAccount> accounts,
        List<FinaryPersistenceHelper.ParsedFinaryTransaction> transactions,
        Instant parsedAt
    ) {}

    /**
     * Parse xlsx file and return a preview with mapping suggestions
     */
    public FinaryPreviewResponse preview(MultipartFile file, Long memberId) {
        try {
            Workbook workbook = WorkbookFactory.create(file.getInputStream());
            ParsedFinaryData parsed = parseXlsx(workbook);

            String fileToken = UUID.randomUUID().toString();
            cache.put(fileToken, parsed);

            List<FinaryAccountPreview> previews = parsed.accounts.stream()
                .map(acc -> new FinaryAccountPreview(
                    slugify(acc.category() + "_" + acc.name()),
                    acc.name(),
                    acc.institution(),
                    acc.category(),
                    FinaryPersistenceHelper.suggestTypeFromDisplayCategory(acc.category()),
                    acc.balance().doubleValue(),
                    acc.currency(),
                    (int) parsed.transactions.stream()
                        .filter(tx -> tx.accountName().equalsIgnoreCase(acc.name()))
                        .count()
                ))
                .collect(Collectors.toList());

            List<Account> existingAccounts = accountRepository.findAllByMemberIdOrderByCreatedAtAsc(memberId);
            List<AccountResponse> existing = existingAccounts.stream()
                .map(a -> AccountResponse.from(a, a.getCurrentBalance()))
                .collect(Collectors.toList());

            List<FinaryAccountMatcher.FinaryRow> rows = parsed.accounts.stream()
                .map(acc -> new FinaryAccountMatcher.FinaryRow(
                    slugify(acc.category() + "_" + acc.name()),
                    acc.name(),
                    acc.category(),
                    slugify(acc.name())))
                .toList();
            List<Optional<FinaryAccountMatcher.Suggestion>> matches =
                FinaryAccountMatcher.suggestAll(rows, existingAccounts);
            boolean allAutoMapped = !rows.isEmpty() && matches.stream().allMatch(Optional::isPresent);
            List<FinaryAccountMapping> suggested = new ArrayList<>();
            for (int i = 0; i < parsed.accounts.size(); i++) {
                var acc = parsed.accounts.get(i);
                String finaryId = slugify(acc.category() + "_" + acc.name());
                var match = matches.get(i);
                if (match.isPresent()) {
                    suggested.add(new FinaryAccountMapping(
                        finaryId, acc.name(), acc.category(),
                        FinaryMappingAction.MAP_EXISTING,
                        match.get().account().getId(), null));
                } else {
                    suggested.add(new FinaryAccountMapping(
                        finaryId, acc.name(), acc.category(),
                        FinaryMappingAction.CREATE_NEW,
                        null, null));
                }
            }

            int totalTx = (int) parsed.transactions.stream().count();

            return new FinaryPreviewResponse(previews, existing, totalTx, fileToken, allAutoMapped, suggested);

        } catch (IOException e) {
            throw new IllegalArgumentException("That file isn't a valid Excel spreadsheet (.xlsx).", e);
        }
    }

    /**
     * Execute the import: create/map accounts, reconstruct balance snapshots, import transactions
     */
    @Transactional
    public FinaryImportResultResponse executeImport(FinaryImportRequest req, Long memberId) {
        ParsedFinaryData parsed = cache.get(req.fileToken());
        if (parsed == null) {
            throw new IllegalArgumentException("Preview expired or invalid -- please re-upload the file");
        }

        FamilyMember member = familyMemberRepository.findById(memberId)
            .orElseThrow(() -> new ResourceNotFoundException("Family member not found"));

        int accountsCreated = 0;
        int accountsMapped = 0;
        int accountsSkipped = 0;
        int snapshotsCreated = 0;
        int transactionsImported = 0;
        List<ImportedAccountSummary> imported = new ArrayList<>();

        Map<String, FinaryPersistenceHelper.ParsedFinaryAccount> finaryByKey = parsed.accounts.stream()
            .collect(Collectors.toMap(
                acc -> buildParsedAccountKey(acc.category(), acc.name()),
                a -> a,
                (a, b) -> a
            ));

        for (FinaryAccountMapping mapping : req.mappings()) {
            FinaryPersistenceHelper.ParsedFinaryAccount finaryAcc = finaryByKey.get(
                mapping.finaryId() != null
                    ? mapping.finaryId()
                    : buildParsedAccountKey(mapping.finaryCategory(), mapping.finaryName())
            );
            if (finaryAcc == null) continue;

            Account account = null;

            if (mapping.action() == com.picsou.dto.FinaryMappingAction.SKIP) {
                accountsSkipped++;
                continue;
            } else if (mapping.action() == com.picsou.dto.FinaryMappingAction.MAP_EXISTING) {
                account = accountRepository.findByIdAndMemberId(mapping.targetAccountId(), memberId)
                    .orElseThrow(() -> new IllegalArgumentException(
                        "Account " + mapping.targetAccountId() + " not found"
                    ));
                accountsMapped++;
            } else if (mapping.action() == com.picsou.dto.FinaryMappingAction.CREATE_NEW) {
                NewAccountDetails det = mapping.newAccount();
                String slug = slugify(finaryAcc.name());
                String externalId = "finary_" + finaryAcc.category() + "_" + slug;

                account = Account.builder()
                    .member(member)
                    .name(det.name())
                    .type(det.type())
                    .provider(det.provider())
                    .currency(det.currency())
                    .currentBalance(finaryAcc.balance())
                    .isManual(true)
                    .color(det.color() != null ? det.color() : FinaryPersistenceHelper.defaultColorForType(det.type()))
                    .externalAccountId(externalId)
                    .build();

                account = accountRepository.save(account);
                accountsCreated++;
            }

            if (account != null) {
                // Reconstruct balance snapshots from transactions
                snapshotsCreated += persistenceHelper.reconstructSnapshots(account, finaryAcc, parsed.transactions);

                // Import transactions for this account
                transactionsImported += persistenceHelper.importTransactions(account, finaryAcc, parsed.transactions);

                imported.add(new ImportedAccountSummary(
                    account.getId(),
                    account.getName(),
                    account.getType(),
                    account.getCurrentBalance().doubleValue(),
                    account.getColor()
                ));
            }
        }

        cache.remove(req.fileToken());

        return new FinaryImportResultResponse(
            accountsCreated, accountsMapped, accountsSkipped,
            snapshotsCreated, transactionsImported, imported
        );
    }


    /**
     * Parse the xlsx file into structured data
     */
    private ParsedFinaryData parseXlsx(Workbook workbook) throws IOException {
        List<FinaryPersistenceHelper.ParsedFinaryAccount> accounts = new ArrayList<>();
        List<FinaryPersistenceHelper.ParsedFinaryTransaction> transactions = new ArrayList<>();

        // Parse account sheets
        for (String categoryName : KNOWN_CATEGORIES) {
            Sheet sheet = workbook.getSheet(categoryName);
            if (sheet == null) continue;

            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;

                try {
                    String name = cellString(row, 0);
                    String institution = cellString(row, 1);
                    BigDecimal balance = cellNumeric(row, 2);
                    String currency = cellString(row, 3);

                    if (name != null && !name.isBlank() && balance != null) {
                        accounts.add(new FinaryPersistenceHelper.ParsedFinaryAccount(
                            name.trim(),
                            institution != null ? institution.trim() : "",
                            categoryName,
                            balance,
                            currency != null ? currency.trim() : "EUR"
                        ));
                    }
                } catch (Exception e) {
                    log.warn("Skipping malformed account row in {}: {}", categoryName, e.getMessage());
                }
            }
        }

        // Parse transactions sheet
        Sheet txSheet = workbook.getSheet("Transactions");
        if (txSheet != null) {
            for (int i = 1; i <= txSheet.getLastRowNum(); i++) {
                Row row = txSheet.getRow(i);
                if (row == null) continue;

                try {
                    String category = cellString(row, 0);
                    String dateStr = cellString(row, 1);
                    String name = cellString(row, 2);
                    BigDecimal amount = cellNumeric(row, 3);
                    String type = cellString(row, 4);
                    String accountName = cellString(row, 5);
                    String institution = cellString(row, 6);
                    String currency = cellString(row, 7);

                    if (dateStr != null && amount != null && accountName != null) {
                        LocalDate date = LocalDate.parse(dateStr.trim(), DATE_PARSER);
                        transactions.add(new FinaryPersistenceHelper.ParsedFinaryTransaction(
                            accountName.trim(),
                            date,
                            name != null ? name.trim() : "",
                            amount,
                            type != null ? type.trim() : "",
                            category != null ? category.trim() : "",
                            currency != null ? currency.trim() : "EUR"
                        ));
                    }
                } catch (DateTimeParseException e) {
                    log.warn("Skipping transaction with unparseable date: {}", cellString(row, 1));
                } catch (Exception e) {
                    log.warn("Skipping malformed transaction row: {}", e.getMessage());
                }
            }
        }

        workbook.close();
        return new ParsedFinaryData(accounts, transactions, Instant.now());
    }

    private String cellString(Row row, int col) {
        Cell cell = row.getCell(col);
        if (cell == null) return null;
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue();
            case NUMERIC -> String.valueOf((long) cell.getNumericCellValue());
            default -> null;
        };
    }

    private String buildParsedAccountKey(String category, String name) {
        return slugify(category + "_" + name);
    }

    private BigDecimal cellNumeric(Row row, int col) {
        Cell cell = row.getCell(col);
        if (cell == null) return null;
        if (cell.getCellType() == CellType.NUMERIC) {
            return BigDecimal.valueOf(cell.getNumericCellValue());
        }
        return null;
    }


    private String slugify(String input) {
        return input.toLowerCase()
            .replaceAll("[^a-z0-9]+", "_")
            .replaceAll("^_|_$", "");
    }

    /**
     * Cleanup old cache entries (older than 30 minutes)
     */
    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    void cleanupExpiredCache() {
        Instant thirtyMinutesAgo = Instant.now().minusSeconds(1800);
        cache.entrySet().removeIf(e -> e.getValue().parsedAt.isBefore(thirtyMinutesAgo));
    }
}
