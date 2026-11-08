package com.picsou.export;

import com.fasterxml.jackson.core.JsonGenerator;
import com.picsou.model.AppUser;
import com.picsou.model.PeerLoanRepayment;
import com.picsou.repository.PeerLoanRepaymentRepository;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;

import static com.picsou.export.AccountsExporter.writeBigDecimal;
import static com.picsou.export.ProfileExporter.nullSafe;
import static com.picsou.export.ProfileExporter.writeInstant;

/**
 * The dated repayment ledger of every peer loan of the member. These rows are the only
 * source of truth for the remaining capital, so leaving them out would export a loan
 * without its history.
 */
@Component
class PeerLoanRepaymentsExporter implements EntityExporter {

    private final PeerLoanRepaymentRepository repaymentRepository;

    PeerLoanRepaymentsExporter(PeerLoanRepaymentRepository repaymentRepository) {
        this.repaymentRepository = repaymentRepository;
    }

    @Override
    public String name() {
        return "peer_loan_repayments";
    }

    @Override
    public List<String> csvHeader() {
        return List.of(
            "id", "account_id", "paid_on", "amount", "kind", "note",
            "cash_transaction_id", "cash_leg_owned", "created_at", "updated_at"
        );
    }

    @Override
    public void writeCsv(AppUser user, ExportContext ctx, CsvWriter csv) throws IOException {
        for (PeerLoanRepayment r : repaymentRepository.findAllByAccountMemberIdOrderByPaidOnAscIdAsc(user.getMember().getId())) {
            csv.writeRow(List.of(
                String.valueOf(r.getId()),
                String.valueOf(r.getAccount().getId()),
                r.getPaidOn() == null ? "" : r.getPaidOn().toString(),
                r.getAmount() == null ? "" : r.getAmount().toPlainString(),
                r.getKind() == null ? "" : r.getKind().name(),
                nullSafe(r.getNote()),
                r.getCashTransactionId() == null ? "" : String.valueOf(r.getCashTransactionId()),
                String.valueOf(r.isCashLegOwned()),
                r.getCreatedAt() == null ? "" : r.getCreatedAt().toString(),
                r.getUpdatedAt() == null ? "" : r.getUpdatedAt().toString()
            ));
        }
    }

    @Override
    public void writeJson(AppUser user, ExportContext ctx, JsonGenerator json) throws IOException {
        json.writeStartArray();
        for (PeerLoanRepayment r : repaymentRepository.findAllByAccountMemberIdOrderByPaidOnAscIdAsc(user.getMember().getId())) {
            json.writeStartObject();
            json.writeNumberField("id", r.getId());
            json.writeNumberField("account_id", r.getAccount().getId());
            json.writeStringField("paid_on", r.getPaidOn() == null ? null : r.getPaidOn().toString());
            writeBigDecimal(json, "amount", r.getAmount());
            json.writeStringField("kind", r.getKind() == null ? null : r.getKind().name());
            json.writeStringField("note", r.getNote());
            if (r.getCashTransactionId() == null) {
                json.writeNullField("cash_transaction_id");
            } else {
                json.writeNumberField("cash_transaction_id", r.getCashTransactionId());
            }
            json.writeBooleanField("cash_leg_owned", r.isCashLegOwned());
            writeInstant(json, "created_at", r.getCreatedAt());
            writeInstant(json, "updated_at", r.getUpdatedAt());
            json.writeEndObject();
        }
        json.writeEndArray();
    }
}
