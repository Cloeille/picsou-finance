package com.picsou.imports;

import com.picsou.dto.ColumnMappingDto;
import com.picsou.imports.csv.CsvDialect;
import com.picsou.imports.csv.CsvValueParser;
import com.picsou.model.Account;
import com.picsou.model.Transaction;
import com.picsou.model.TransactionType;
import com.picsou.service.InstrumentFieldResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

/**
 * Maps one raw CSV row into an unsaved BUY/SELL {@link Transaction} using a user-confirmed
 * column mapping and {@link CsvDialect}. Reuses {@link InstrumentFieldResolver} (so an imported
 * ISIN and a manually-typed one collapse to the same position) and {@link TransactionAmountCalculator}
 * (so the stored signed amount — fees included — never drifts from the manual-entry path).
 *
 * <p>Invalid rows throw {@link IllegalArgumentException} with a user-safe message; the import
 * service turns that into a per-row error rather than failing the whole file.
 */
@Component
@RequiredArgsConstructor
public class TransactionRowMapper {

    private final InstrumentFieldResolver instrumentFieldResolver;

    public Transaction map(List<String> row, ColumnMappingDto mapping, CsvDialect dialect,
                           Map<String, String> sideValueMap, boolean feesIncludedInAmount,
                           Account account) {

        TransactionType side = resolveSide(cell(row, mapping.side()), cell(row, mapping.amount()),
            dialect, sideValueMap);

        LocalDate date = parseDate(cell(row, mapping.date()), dialect);
        if (date == null) {
            throw new IllegalArgumentException("Missing date");
        }

        BigDecimal quantity = parseDecimal(cell(row, mapping.quantity()), dialect, "quantity");
        if (quantity == null || quantity.signum() <= 0) {
            throw new IllegalArgumentException("Missing or non-positive quantity");
        }

        BigDecimal fees = parseDecimal(cell(row, mapping.fees()), dialect, "fees");
        if (fees == null) {
            fees = BigDecimal.ZERO;
        } else if (fees.signum() < 0) {
            throw new IllegalArgumentException("Fees cannot be negative");
        }

        BigDecimal price = resolvePrice(row, mapping, dialect, side, quantity, fees, feesIncludedInAmount);

        String currency = cell(row, mapping.currency());
        if (currency == null || currency.isBlank()) {
            currency = account.getCurrency();
        }

        InstrumentFieldResolver.ResolvedInstrument instrument =
            instrumentFieldResolver.resolve(cell(row, mapping.tickerOrIsin()), cell(row, mapping.name()));
        if (instrument == null) {
            throw new IllegalArgumentException("Missing ticker/ISIN");
        }

        BigDecimal amount = TransactionAmountCalculator.signedAmount(side, quantity, price, fees);

        return Transaction.builder()
            .account(account)
            .date(date)
            .description(instrument.description())
            .amount(amount)
            .txType(side)
            .ticker(instrument.ticker())
            .name(instrument.name())
            .quantity(quantity)
            .pricePerUnit(price)
            .fees(fees)
            .isManual(true)
            .nativeCurrency(currency)
            .build();
    }

    private TransactionType resolveSide(String sideCell, String amountCell, CsvDialect dialect,
                                        Map<String, String> sideValueMap) {
        if (sideCell != null && !sideCell.isBlank()) {
            String raw = sideCell.trim();
            if (sideValueMap != null) {
                for (Map.Entry<String, String> e : sideValueMap.entrySet()) {
                    if (e.getKey().equalsIgnoreCase(raw)) {
                        return TransactionType.valueOf(e.getValue().trim().toUpperCase());
                    }
                }
            }
            String lower = raw.toLowerCase();
            // Explicit tokens first; a bare "s"/"b" only matches exactly (so e.g. "Souscription"
            // is not mistaken for a sell), then fall through to the amount-sign heuristic.
            if (lower.equals("s") || lower.contains("vent") || lower.contains("sell") || lower.contains("sale")) {
                return TransactionType.SELL;
            }
            if (lower.equals("b") || lower.contains("ach") || lower.contains("buy")) {
                return TransactionType.BUY;
            }
        }
        // Fall back to the sign of the amount column: outflow = BUY, inflow = SELL.
        BigDecimal amount = parseDecimal(amountCell, dialect, "amount");
        if (amount != null) {
            return amount.signum() < 0 ? TransactionType.BUY : TransactionType.SELL;
        }
        throw new IllegalArgumentException("Cannot determine BUY/SELL");
    }

    private BigDecimal resolvePrice(List<String> row, ColumnMappingDto mapping, CsvDialect dialect,
                                    TransactionType side, BigDecimal quantity, BigDecimal fees,
                                    boolean feesIncludedInAmount) {
        BigDecimal price = parseDecimal(cell(row, mapping.unitPrice()), dialect, "unit price");
        if (price != null) {
            if (price.signum() < 0) {
                throw new IllegalArgumentException("Unit price cannot be negative");
            }
            return price;
        }
        // Derive the unit price from the total amount when no per-unit price column is mapped.
        BigDecimal amount = parseDecimal(cell(row, mapping.amount()), dialect, "amount");
        if (amount == null) {
            throw new IllegalArgumentException("Missing unit price (or amount)");
        }
        BigDecimal gross = amount.abs();
        if (feesIncludedInAmount) {
            // The amount nets fees: recover the fee-free gross before dividing by quantity.
            gross = side == TransactionType.SELL ? gross.add(fees) : gross.subtract(fees);
        }
        return gross.divide(quantity, 8, RoundingMode.HALF_UP);
    }

    private static String cell(List<String> row, Integer index) {
        if (index == null || index < 0 || index >= row.size()) {
            return null;
        }
        String v = row.get(index);
        return v == null ? null : v.trim();
    }

    private static BigDecimal parseDecimal(String raw, CsvDialect dialect, String field) {
        try {
            return CsvValueParser.parseDecimal(raw, dialect.decimal());
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Invalid " + field + " '" + raw + "'", ex);
        }
    }

    private static LocalDate parseDate(String raw, CsvDialect dialect) {
        try {
            return CsvValueParser.parseDate(raw, dialect.dateFormat());
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException("Invalid date '" + raw + "'", ex);
        }
    }
}
