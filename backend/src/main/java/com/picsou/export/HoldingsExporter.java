package com.picsou.export;

import com.fasterxml.jackson.core.JsonGenerator;
import com.picsou.model.Account;
import com.picsou.model.AccountHolding;
import com.picsou.model.AppUser;
import com.picsou.repository.AccountHoldingRepository;
import com.picsou.repository.AccountRepository;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;

import static com.picsou.export.AccountsExporter.writeBigDecimal;
import static com.picsou.export.ProfileExporter.nullSafe;
import static com.picsou.export.ProfileExporter.writeInstant;

@Component
class HoldingsExporter implements EntityExporter {

    private final AccountRepository accountRepository;
    private final AccountHoldingRepository holdingRepository;

    HoldingsExporter(AccountRepository accountRepository, AccountHoldingRepository holdingRepository) {
        this.accountRepository = accountRepository;
        this.holdingRepository = holdingRepository;
    }

    @Override
    public String name() {
        return "holdings";
    }

    @Override
    public List<String> csvHeader() {
        return List.of(
            "id", "account_id", "ticker", "name", "quantity", "average_buy_in",
            "current_price", "quote_currency", "provider_value_eur", "provider_pnl_eur",
            "last_synced_at", "created_at", "updated_at"
        );
    }

    @Override
    public void writeCsv(AppUser user, ExportContext ctx, CsvWriter csv) throws IOException {
        for (AccountHolding h : holdings(user)) {
            csv.writeRow(List.of(
                String.valueOf(h.getId()),
                String.valueOf(h.getAccount().getId()),
                nullSafe(h.getTicker()),
                nullSafe(h.getName()),
                nullSafe(h.getQuantity() == null ? null : h.getQuantity().toPlainString()),
                nullSafe(h.getAverageBuyIn() == null ? null : h.getAverageBuyIn().toPlainString()),
                nullSafe(h.getCurrentPrice() == null ? null : h.getCurrentPrice().toPlainString()),
                nullSafe(h.getQuoteCurrency()),
                nullSafe(h.getProviderValueEur() == null ? null : h.getProviderValueEur().toPlainString()),
                nullSafe(h.getProviderPnlEur() == null ? null : h.getProviderPnlEur().toPlainString()),
                nullSafe(h.getLastSyncedAt() == null ? null : h.getLastSyncedAt().toString()),
                nullSafe(h.getCreatedAt() == null ? null : h.getCreatedAt().toString()),
                nullSafe(h.getUpdatedAt() == null ? null : h.getUpdatedAt().toString())
            ));
        }
    }

    @Override
    public void writeJson(AppUser user, ExportContext ctx, JsonGenerator json) throws IOException {
        json.writeStartArray();
        for (AccountHolding h : holdings(user)) {
            json.writeStartObject();
            json.writeNumberField("id", h.getId());
            json.writeNumberField("account_id", h.getAccount().getId());
            json.writeStringField("ticker", h.getTicker());
            json.writeStringField("name", h.getName());
            writeBigDecimal(json, "quantity", h.getQuantity());
            writeBigDecimal(json, "average_buy_in", h.getAverageBuyIn());
            writeBigDecimal(json, "current_price", h.getCurrentPrice());
            json.writeStringField("quote_currency", h.getQuoteCurrency());
            writeBigDecimal(json, "provider_value_eur", h.getProviderValueEur());
            writeBigDecimal(json, "provider_pnl_eur", h.getProviderPnlEur());
            writeInstant(json, "last_synced_at", h.getLastSyncedAt());
            writeInstant(json, "created_at", h.getCreatedAt());
            writeInstant(json, "updated_at", h.getUpdatedAt());
            json.writeEndObject();
        }
        json.writeEndArray();
    }

    private List<AccountHolding> holdings(AppUser user) {
        Long memberId = user.getMember().getId();
        return accountRepository.findAllByMemberIdOrderByCreatedAtAsc(memberId).stream()
            .flatMap(a -> holdingRepository.findByAccount_Id(a.getId()).stream())
            .toList();
    }
}
