package com.picsou.imports;

import com.picsou.dto.ColumnMappingDto;
import com.picsou.imports.csv.CsvDialect;
import com.picsou.imports.csv.DecimalStyle;
import com.picsou.model.Account;
import com.picsou.model.Transaction;
import com.picsou.model.TransactionType;
import com.picsou.service.InstrumentFieldResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransactionRowMapperTest {

    @Mock InstrumentFieldResolver instrumentFieldResolver;
    TransactionRowMapper mapper;

    private final Account account = Account.builder().id(1L).currency("EUR").build();
    private final CsvDialect dialect = new CsvDialect(',', DecimalStyle.DOT, "yyyy-MM-dd");

    @BeforeEach
    void setUp() {
        mapper = new TransactionRowMapper(instrumentFieldResolver);
    }

    private void stubResolver() {
        lenient().when(instrumentFieldResolver.resolve(any(), any()))
            .thenReturn(new InstrumentFieldResolver.ResolvedInstrument("AAPL", "Apple", "Apple"));
    }

    // columns: 0=date 1=side 2=ticker 3=qty 4=price 5=fees
    private ColumnMappingDto mappingWithPrice() {
        return new ColumnMappingDto(0, 1, 2, null, 3, 4, 5, null, null);
    }

    @Test
    void buyRow_signsAmountWithFees() {
        stubResolver();
        List<String> row = List.of("2024-01-15", "BUY", "AAPL", "10", "85.20", "1.00");

        Transaction tx = mapper.map(row, mappingWithPrice(), dialect, null, false, account);

        assertThat(tx.getTxType()).isEqualTo(TransactionType.BUY);
        assertThat(tx.getQuantity()).isEqualByComparingTo("10");
        assertThat(tx.getPricePerUnit()).isEqualByComparingTo("85.20");
        assertThat(tx.getFees()).isEqualByComparingTo("1.00");
        assertThat(tx.getAmount()).isEqualByComparingTo("-853.00"); // -(10*85.20 + 1.00)
        assertThat(tx.getTicker()).isEqualTo("AAPL");
        assertThat(tx.isManual()).isTrue();
        assertThat(tx.getNativeCurrency()).isEqualTo("EUR");
    }

    @Test
    void sellRow_signsAmountWithFees() {
        stubResolver();
        List<String> row = List.of("2024-06-02", "SELL", "AAPL", "10", "92.50", "1.00");

        Transaction tx = mapper.map(row, mappingWithPrice(), dialect, null, false, account);

        assertThat(tx.getTxType()).isEqualTo(TransactionType.SELL);
        assertThat(tx.getAmount()).isEqualByComparingTo("924.00"); // +(10*92.50 - 1.00)
    }

    @Test
    void sideResolvedFromValueMap() {
        stubResolver();
        List<String> row = List.of("2024-01-15", "Achat", "AAPL", "10", "85.20", "0");

        Transaction tx = mapper.map(row, mappingWithPrice(), dialect, Map.of("Achat", "BUY"), false, account);

        assertThat(tx.getTxType()).isEqualTo(TransactionType.BUY);
    }

    @Test
    void sideResolvedFromAmountSignWhenNoSideColumn() {
        stubResolver();
        // columns: 0=date 1=ticker 2=qty 3=amount ; no side, no unit price
        ColumnMappingDto mapping = new ColumnMappingDto(0, null, 1, null, 2, null, null, null, 3);
        List<String> row = List.of("2024-01-15", "AAPL", "10", "-853.00");

        Transaction tx = mapper.map(row, mapping, dialect, null, false, account);

        assertThat(tx.getTxType()).isEqualTo(TransactionType.BUY);      // negative amount = outflow = BUY
        assertThat(tx.getPricePerUnit()).isEqualByComparingTo("85.30"); // 853 / 10
    }

    @Test
    void priceDerivedFromAmountWithFeesIncluded() {
        stubResolver();
        // columns: 0=date 1=side 2=ticker 3=qty 4=amount 5=fees ; no unit price
        ColumnMappingDto mapping = new ColumnMappingDto(0, 1, 2, null, 3, null, 5, null, 4);
        List<String> row = List.of("2024-01-15", "BUY", "AAPL", "10", "-854.00", "1.00");

        Transaction tx = mapper.map(row, mapping, dialect, null, true, account);

        // amount nets fees: gross = 854 - 1 = 853 → unit price 85.30
        assertThat(tx.getPricePerUnit()).isEqualByComparingTo("85.30");
        assertThat(tx.getFees()).isEqualByComparingTo("1.00");
        assertThat(tx.getAmount()).isEqualByComparingTo("-854.00"); // -(10*85.30 + 1.00)
    }

    @Test
    void sideSingleLetterAndExplicitTokens() {
        stubResolver();
        assertThat(mapper.map(List.of("2024-01-15", "s", "AAPL", "10", "85.20", "0"),
            mappingWithPrice(), dialect, null, false, account).getTxType()).isEqualTo(TransactionType.SELL);
        assertThat(mapper.map(List.of("2024-01-15", "Sale", "AAPL", "10", "85.20", "0"),
            mappingWithPrice(), dialect, null, false, account).getTxType()).isEqualTo(TransactionType.SELL);
        assertThat(mapper.map(List.of("2024-01-15", "b", "AAPL", "10", "85.20", "0"),
            mappingWithPrice(), dialect, null, false, account).getTxType()).isEqualTo(TransactionType.BUY);
    }

    @Test
    void ambiguousSideWordWithNoAmount_throws() {
        stubResolver();
        // "Souscription" starts with 's' but must NOT be read as a sell; with no amount column
        // the side is genuinely undeterminable.
        List<String> row = List.of("2024-01-15", "Souscription", "AAPL", "10", "85.20", "0");
        assertThatThrownBy(() -> mapper.map(row, mappingWithPrice(), dialect, null, false, account))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("BUY/SELL");
    }

    @Test
    void missingDate_throws() {
        stubResolver();
        List<String> row = List.of("", "BUY", "AAPL", "10", "85.20", "1.00");

        assertThatThrownBy(() -> mapper.map(row, mappingWithPrice(), dialect, null, false, account))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("date");
    }

    @Test
    void nonPositiveQuantity_throws() {
        stubResolver();
        List<String> row = List.of("2024-01-15", "BUY", "AAPL", "0", "85.20", "1.00");

        assertThatThrownBy(() -> mapper.map(row, mappingWithPrice(), dialect, null, false, account))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("quantity");
    }

    @Test
    void blankTicker_throws() {
        when(instrumentFieldResolver.resolve(any(), any())).thenReturn(null);
        List<String> row = List.of("2024-01-15", "BUY", "", "10", "85.20", "1.00");

        assertThatThrownBy(() -> mapper.map(row, mappingWithPrice(), dialect, null, false, account))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("ticker");
    }
}
