package com.picsou.service;

import com.picsou.dto.ColumnMappingDto;
import com.picsou.dto.CsvDialectDto;
import com.picsou.dto.TransactionImportPreviewResponse;
import com.picsou.dto.TransactionImportRequest;
import com.picsou.dto.TransactionImportResultResponse;
import com.picsou.dto.TransactionImportResultResponse.RowError;
import com.picsou.exception.ResourceNotFoundException;
import com.picsou.imports.TransactionRowMapper;
import com.picsou.imports.csv.CsvDialect;
import com.picsou.imports.csv.CsvDialectDetector;
import com.picsou.imports.csv.CsvReader;
import com.picsou.imports.csv.CsvValueParser;
import com.picsou.imports.csv.DecimalStyle;
import com.picsou.model.Account;
import com.picsou.model.Transaction;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Two-phase CSV import of investment transactions into a single target account. Phase one
 * ({@link #preview}) parses the upload, guesses the dialect + column mapping, and caches the raw
 * file under a token bound to the account. Phase two ({@link #executeImport}) re-parses with the
 * user-confirmed dialect, maps each row to a manual BUY/SELL transaction, and recomputes holdings
 * once. Mirrors {@code FinaryImportService}'s token/cache/TTL shape.
 */
@Service
@RequiredArgsConstructor
public class TransactionImportService {

    private static final Logger log = LoggerFactory.getLogger(TransactionImportService.class);

    private static final int SAMPLE_ROWS = 15;
    private static final String PREVIEW_EXPIRED = "Preview expired or invalid -- please re-upload the file";

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final HoldingComputeService holdingComputeService;
    private final TransactionRowMapper rowMapper;

    private final ConcurrentHashMap<String, CachedCsv> cache = new ConcurrentHashMap<>();

    record CachedCsv(Long accountId, String content, Instant parsedAt) {}

    // --- Phase 1: preview -------------------------------------------------------------------

    public TransactionImportPreviewResponse preview(Long accountId, Long memberId, MultipartFile file) {
        getInvestmentAccount(accountId, memberId);

        String content = readContent(file);
        char delimiter = CsvDialectDetector.detectDelimiter(content);
        List<List<String>> rows = CsvReader.parse(content, delimiter);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("The file has no rows");
        }

        DecimalStyle decimal = CsvDialectDetector.detectDecimal(rows);
        String dateFormat = CsvDialectDetector.detectDateFormat(rows);
        boolean hasHeader = looksLikeHeader(rows.get(0), decimal);

        List<List<String>> dataRows = hasHeader
            ? (rows.size() > 1 ? rows.subList(1, rows.size()) : List.of())
            : rows;
        List<String> detectedColumns = hasHeader ? trimAll(rows.get(0)) : genericColumns(rows);
        ColumnMappingDto suggestedMapping = hasHeader ? guessMapping(rows.get(0)) : emptyMapping();
        List<List<String>> sampleRows = dataRows.stream().limit(SAMPLE_ROWS).toList();

        String fileToken = UUID.randomUUID().toString();
        cache.put(fileToken, new CachedCsv(accountId, content, Instant.now()));

        CsvDialectDto dialect = new CsvDialectDto(String.valueOf(delimiter), decimal.name(), dateFormat);
        return new TransactionImportPreviewResponse(
            fileToken, detectedColumns, sampleRows, dataRows.size(), hasHeader, dialect, suggestedMapping);
    }

    // --- Phase 2: execute -------------------------------------------------------------------

    @Transactional
    public TransactionImportResultResponse executeImport(Long accountId, Long memberId, TransactionImportRequest req) {
        Account account = getInvestmentAccount(accountId, memberId);

        CachedCsv cached = cache.get(req.fileToken());
        if (cached == null) {
            throw new IllegalArgumentException(PREVIEW_EXPIRED);
        }
        // Bind the token to its account so a preview cannot be replayed against another account.
        if (!cached.accountId().equals(accountId)) {
            throw new IllegalArgumentException("Preview does not belong to this account");
        }
        // Consume the token BEFORE writing anything. Removing it after saveAll left a window the
        // width of the whole import in which a second execute on the same preview (a double click,
        // a request the client retried after a timeout) passed the checks above too and saved every
        // row a second time: duplicated transactions, a cost basis and a realized P&L off by exactly
        // one import, and nothing to flag it. remove(key, value) is atomic, so of two callers racing
        // on one token exactly one gets past this line.
        if (!cache.remove(req.fileToken(), cached)) {
            throw new IllegalArgumentException(PREVIEW_EXPIRED);
        }

        // If this attempt does not reach the database, the preview goes back into the cache so
        // the user can retry without a new upload. saveAll may only queue the rows: the INSERTs
        // run at flush, on commit, after this method has returned, so a failure there never
        // passes through a catch block here. Hooking the transaction's completion covers it.
        boolean transactional = TransactionSynchronizationManager.isSynchronizationActive();
        if (transactional) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status == STATUS_ROLLED_BACK) {
                        cache.putIfAbsent(req.fileToken(), cached);
                    }
                }
            });
        }
        try {
            return importRows(account, cached, req);
        } catch (RuntimeException ex) {
            // No live transaction to hook (the bean called directly, as in the unit tests): hand
            // the preview back here. With one, the synchronization above does it once the
            // rollback has actually completed.
            if (!transactional) {
                cache.putIfAbsent(req.fileToken(), cached);
            }
            throw ex;
        }
    }

    private TransactionImportResultResponse importRows(Account account, CachedCsv cached, TransactionImportRequest req) {
        CsvDialect dialect = toDialect(req.dialect());
        List<List<String>> rows = CsvReader.parse(cached.content(), dialect.delimiter());
        List<List<String>> dataRows = req.hasHeaderRow() && !rows.isEmpty() ? rows.subList(1, rows.size()) : rows;

        List<Transaction> toSave = new ArrayList<>();
        List<RowError> errors = new ArrayList<>();
        int rowNumber = req.hasHeaderRow() ? 2 : 1; // 1-based, header is row 1

        for (List<String> row : dataRows) {
            try {
                toSave.add(rowMapper.map(row, req.mapping(), dialect,
                    req.sideValueMap(), req.feesIncludedInAmount(), account));
            } catch (IllegalArgumentException ex) {
                errors.add(new RowError(rowNumber, ex.getMessage()));
            }
            rowNumber++;
        }

        if (!toSave.isEmpty()) {
            transactionRepository.saveAll(toSave);
            holdingComputeService.recomputeHoldings(account);
        }

        log.info("CSV import for account {}: {} imported, {} skipped", account.getId(), toSave.size(), errors.size());
        return new TransactionImportResultResponse(toSave.size(), errors.size(), errors);
    }

    // --- Helpers ----------------------------------------------------------------------------

    private Account getInvestmentAccount(Long accountId, Long memberId) {
        Account account = accountRepository.findByIdAndMemberId(accountId, memberId)
            .orElseThrow(() -> ResourceNotFoundException.account(accountId));
        if (!account.getType().isInvestment()) {
            throw new IllegalArgumentException(
                "CSV transaction import is only available for investment accounts (PEA, CTO, crypto)");
        }
        if (!account.isManual()) {
            throw new IllegalArgumentException(
                "CSV transaction import is only available for manual investment accounts");
        }
        return account;
    }

    private String readContent(MultipartFile file) {
        try {
            String content = new String(file.getBytes(), StandardCharsets.UTF_8);
            if (content.isBlank()) {
                throw new IllegalArgumentException("The file is empty");
            }
            return content;
        } catch (IOException ex) {
            // The 400 stays deliberately vague; the cause is what tells an operator whether the
            // upload was truncated or the temp store is broken.
            log.warn("Could not read uploaded transaction file '{}'", file.getOriginalFilename(), ex);
            throw new IllegalArgumentException("Could not read the uploaded file", ex);
        }
    }

    private CsvDialect toDialect(CsvDialectDto dto) {
        char delimiter = (dto == null || dto.delimiter() == null || dto.delimiter().isEmpty())
            ? ',' : dto.delimiter().charAt(0);

        DecimalStyle decimal = DecimalStyle.DOT;
        if (dto != null && dto.decimal() != null) {
            try {
                decimal = DecimalStyle.valueOf(dto.decimal().trim().toUpperCase());
            } catch (IllegalArgumentException ex) {
                log.warn("Unknown CSV decimal style '{}' — falling back to DOT", dto.decimal());
            }
        }

        String dateFormat = (dto == null || dto.dateFormat() == null || dto.dateFormat().isBlank())
            ? "yyyy-MM-dd" : dto.dateFormat().trim();
        try {
            DateTimeFormatter.ofPattern(dateFormat);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Invalid date format pattern '" + dateFormat + "'", ex);
        }
        return new CsvDialect(delimiter, decimal, dateFormat);
    }

    private static boolean looksLikeHeader(List<String> row, DecimalStyle style) {
        for (String cell : row) {
            try {
                if (CsvValueParser.parseDecimal(cell, style) != null) {
                    return false; // a numeric cell means this is a data row, not a header
                }
            } catch (NumberFormatException ignored) {
                // non-numeric — consistent with a header cell
            }
        }
        return true;
    }

    private static List<String> trimAll(List<String> row) {
        return row.stream().map(c -> c == null ? "" : c.trim()).toList();
    }

    private static List<String> genericColumns(List<List<String>> rows) {
        int max = rows.stream().mapToInt(List::size).max().orElse(0);
        List<String> cols = new ArrayList<>();
        for (int i = 0; i < max; i++) {
            cols.add("Column " + (i + 1));
        }
        return cols;
    }

    private static ColumnMappingDto emptyMapping() {
        return new ColumnMappingDto(null, null, null, null, null, null, null, null, null);
    }

    private static ColumnMappingDto guessMapping(List<String> header) {
        Integer date = null, side = null, ticker = null, name = null, quantity = null,
            unitPrice = null, fees = null, currency = null, amount = null;
        for (int i = 0; i < header.size(); i++) {
            String h = header.get(i) == null ? "" : header.get(i).toLowerCase().trim();
            if (date == null && h.contains("date")) date = i;
            else if (side == null && (h.contains("sens") || h.contains("side") || h.contains("type")
                || h.contains("operation") || h.contains("sens")))
                side = i;
            else if (ticker == null && (h.contains("isin") || h.contains("ticker") || h.contains("symbol")
                || h.contains("symbole") || h.contains("valeur") || h.contains("instrument")))
                ticker = i;
            else if (name == null && (h.contains("name") || h.contains("nom") || h.contains("libell")
                || h.contains("designation") || h.contains("label")))
                name = i;
            else if (quantity == null && (h.contains("qty") || h.contains("quant") || h.contains("qte")
                || h.contains("nombre") || h.contains("shares") || h.contains("parts")))
                quantity = i;
            else if (fees == null && (h.contains("fee") || h.contains("frais") || h.contains("commission")
                || h.contains("courtage")))
                fees = i;
            else if (unitPrice == null && (h.contains("price") || h.contains("prix") || h.contains("cours")
                || h.contains("unit")) && !h.contains("total"))
                unitPrice = i;
            else if (currency == null && (h.contains("currency") || h.contains("devise")
                || h.contains("monnaie")))
                currency = i;
            else if (amount == null && (h.contains("amount") || h.contains("montant") || h.contains("total")
                || h.contains("net")))
                amount = i;
        }
        return new ColumnMappingDto(date, side, ticker, name, quantity, unitPrice, fees, currency, amount);
    }

    /** Evicts cached uploads older than 30 minutes (copy of the Finary importer's TTL sweep). */
    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    void cleanupExpiredCache() {
        Instant cutoff = Instant.now().minusSeconds(1800);
        cache.entrySet().removeIf(e -> e.getValue().parsedAt().isBefore(cutoff));
    }
}
