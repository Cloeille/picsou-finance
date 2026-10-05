package com.picsou.export;

import com.fasterxml.jackson.core.JsonGenerator;
import com.picsou.model.AccountOwnership;
import com.picsou.model.AppUser;
import com.picsou.repository.AccountOwnershipRepository;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;

import static com.picsou.export.AccountsExporter.writeBigDecimal;
import static com.picsou.export.ProfileExporter.writeInstant;

/**
 * Exports the member's ownership shares.
 *
 * <p>Scoped by {@code member_id}, so an export contains the rows naming this member — their
 * own stake in each split — and not the co-owners' stakes, which are somebody else's data.
 */
@Component
class AccountOwnershipExporter implements EntityExporter {

    private final AccountOwnershipRepository ownershipRepository;

    AccountOwnershipExporter(AccountOwnershipRepository ownershipRepository) {
        this.ownershipRepository = ownershipRepository;
    }

    @Override
    public String name() {
        return "account_ownership";
    }

    @Override
    public List<String> csvHeader() {
        return List.of("id", "account_id", "member_id", "share_percent", "created_at", "updated_at");
    }

    @Override
    public void writeCsv(AppUser user, ExportContext ctx, CsvWriter csv) throws IOException {
        for (AccountOwnership o : rows(user)) {
            csv.writeRow(List.of(
                String.valueOf(o.getId()),
                String.valueOf(o.getAccount().getId()),
                String.valueOf(o.getMember().getId()),
                o.getSharePercent() == null ? "" : o.getSharePercent().toPlainString(),
                o.getCreatedAt() == null ? "" : o.getCreatedAt().toString(),
                o.getUpdatedAt() == null ? "" : o.getUpdatedAt().toString()
            ));
        }
    }

    @Override
    public void writeJson(AppUser user, ExportContext ctx, JsonGenerator json) throws IOException {
        json.writeStartArray();
        for (AccountOwnership o : rows(user)) {
            json.writeStartObject();
            json.writeNumberField("id", o.getId());
            json.writeNumberField("account_id", o.getAccount().getId());
            json.writeNumberField("member_id", o.getMember().getId());
            writeBigDecimal(json, "share_percent", o.getSharePercent());
            writeInstant(json, "created_at", o.getCreatedAt());
            writeInstant(json, "updated_at", o.getUpdatedAt());
            json.writeEndObject();
        }
        json.writeEndArray();
    }

    private List<AccountOwnership> rows(AppUser user) {
        return ownershipRepository.findByMemberId(user.getMember().getId());
    }
}
