package com.picsou.export;

import com.fasterxml.jackson.core.JsonGenerator;
import com.picsou.model.Account;
import com.picsou.model.AppUser;
import com.picsou.repository.AccountRepository;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;

import static com.picsou.export.ProfileExporter.nullSafe;
import static com.picsou.export.ProfileExporter.writeInstant;

@Component
class AccountsExporter implements EntityExporter {

    private final AccountRepository accountRepository;

    AccountsExporter(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    @Override
    public String name() {
        return "accounts";
    }

    @Override
    public List<String> csvHeader() {
        return List.of(
            "id", "name", "type", "provider", "currency", "current_balance", "cash_balance",
            "ticker", "is_manual", "color", "external_account_id",
            "last_synced_at", "created_at", "updated_at"
        );
    }

    @Override
    public void writeCsv(AppUser user, ExportContext ctx, CsvWriter csv) throws IOException {
        for (Account a : accounts(user)) {
            csv.writeRow(List.of(
                String.valueOf(a.getId()),
                nullSafe(a.getName()),
                nullSafe(a.getType() == null ? null : a.getType().name()),
                nullSafe(a.getProvider()),
                nullSafe(a.getCurrency()),
                nullSafe(a.getCurrentBalance() == null ? null : a.getCurrentBalance().toPlainString()),
                nullSafe(a.getCashBalance() == null ? null : a.getCashBalance().toPlainString()),
                nullSafe(a.getTicker()),
                String.valueOf(a.isManual()),
                nullSafe(a.getColor()),
                nullSafe(a.getExternalAccountId()),
                nullSafe(a.getLastSyncedAt() == null ? null : a.getLastSyncedAt().toString()),
                nullSafe(a.getCreatedAt() == null ? null : a.getCreatedAt().toString()),
                nullSafe(a.getUpdatedAt() == null ? null : a.getUpdatedAt().toString())
            ));
        }
    }

    @Override
    public void writeJson(AppUser user, ExportContext ctx, JsonGenerator json) throws IOException {
        json.writeStartArray();
        for (Account a : accounts(user)) {
            json.writeStartObject();
            json.writeNumberField("id", a.getId());
            json.writeStringField("name", a.getName());
            json.writeStringField("type", a.getType() == null ? null : a.getType().name());
            json.writeStringField("provider", a.getProvider());
            json.writeStringField("currency", a.getCurrency());
            writeBigDecimal(json, "current_balance", a.getCurrentBalance());
            writeBigDecimal(json, "cash_balance", a.getCashBalance());
            json.writeStringField("ticker", a.getTicker());
            json.writeBooleanField("is_manual", a.isManual());
            json.writeStringField("color", a.getColor());
            json.writeStringField("external_account_id", a.getExternalAccountId());
            writeInstant(json, "last_synced_at", a.getLastSyncedAt());
            writeInstant(json, "created_at", a.getCreatedAt());
            writeInstant(json, "updated_at", a.getUpdatedAt());
            json.writeEndObject();
        }
        json.writeEndArray();
    }

    private List<Account> accounts(AppUser user) {
        return accountRepository.findAllByMemberIdOrderByCreatedAtAsc(user.getMember().getId());
    }

    static void writeBigDecimal(JsonGenerator json, String field, BigDecimal value) throws IOException {
        if (value == null) json.writeNullField(field);
        else json.writeNumberField(field, value);
    }
}
