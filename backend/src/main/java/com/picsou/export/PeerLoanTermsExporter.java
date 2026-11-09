package com.picsou.export;

import com.fasterxml.jackson.core.JsonGenerator;
import com.picsou.model.AppUser;
import com.picsou.model.PeerLoanTerms;
import com.picsou.repository.PeerLoanTermsRepository;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;

import static com.picsou.export.AccountsExporter.writeBigDecimal;
import static com.picsou.export.ProfileExporter.nullSafe;
import static com.picsou.export.ProfileExporter.writeInstant;

/**
 * The terms of each peer loan (principal, rate, plan, closure). The repayments live in
 * their own table, joined on {@code account_id}.
 */
@Component
class PeerLoanTermsExporter implements EntityExporter {

    private final PeerLoanTermsRepository termsRepository;

    PeerLoanTermsExporter(PeerLoanTermsRepository termsRepository) {
        this.termsRepository = termsRepository;
    }

    @Override
    public String name() {
        return "peer_loan_terms";
    }

    @Override
    public List<String> csvHeader() {
        return List.of(
            "id", "account_id", "counterparty_id", "principal", "lent_at", "expected_back",
            "annual_rate", "rate_period", "planned_monthly_amount", "planned_months",
            "linked_account_id", "lending_transaction_id", "note", "closed_at",
            "created_at", "updated_at"
        );
    }

    @Override
    public void writeCsv(AppUser user, ExportContext ctx, CsvWriter csv) throws IOException {
        for (PeerLoanTerms t : termsRepository.findAllByAccountMemberIdOrderByLentAtAscIdAsc(user.getMember().getId())) {
            csv.writeRow(List.of(
                String.valueOf(t.getId()),
                String.valueOf(t.getAccount().getId()),
                t.getCounterparty() == null ? "" : String.valueOf(t.getCounterparty().getId()),
                t.getPrincipal() == null ? "" : t.getPrincipal().toPlainString(),
                t.getLentAt() == null ? "" : t.getLentAt().toString(),
                t.getExpectedBack() == null ? "" : t.getExpectedBack().toString(),
                t.getAnnualRate() == null ? "" : t.getAnnualRate().toPlainString(),
                t.getRatePeriod() == null ? "" : t.getRatePeriod().name(),
                t.getPlannedMonthlyAmount() == null ? "" : t.getPlannedMonthlyAmount().toPlainString(),
                t.getPlannedMonths() == null ? "" : String.valueOf(t.getPlannedMonths()),
                t.getLinkedAccount() == null ? "" : String.valueOf(t.getLinkedAccount().getId()),
                t.getLendingTransactionId() == null ? "" : String.valueOf(t.getLendingTransactionId()),
                nullSafe(t.getNote()),
                t.getClosedAt() == null ? "" : t.getClosedAt().toString(),
                t.getCreatedAt() == null ? "" : t.getCreatedAt().toString(),
                t.getUpdatedAt() == null ? "" : t.getUpdatedAt().toString()
            ));
        }
    }

    @Override
    public void writeJson(AppUser user, ExportContext ctx, JsonGenerator json) throws IOException {
        json.writeStartArray();
        for (PeerLoanTerms t : termsRepository.findAllByAccountMemberIdOrderByLentAtAscIdAsc(user.getMember().getId())) {
            json.writeStartObject();
            json.writeNumberField("id", t.getId());
            json.writeNumberField("account_id", t.getAccount().getId());
            if (t.getCounterparty() != null) {
                json.writeNumberField("counterparty_id", t.getCounterparty().getId());
            } else {
                json.writeNullField("counterparty_id");
            }
            writeBigDecimal(json, "principal", t.getPrincipal());
            json.writeStringField("lent_at", t.getLentAt() == null ? null : t.getLentAt().toString());
            json.writeStringField("expected_back", t.getExpectedBack() == null ? null : t.getExpectedBack().toString());
            writeBigDecimal(json, "annual_rate", t.getAnnualRate());
            json.writeStringField("rate_period", t.getRatePeriod() == null ? null : t.getRatePeriod().name());
            writeBigDecimal(json, "planned_monthly_amount", t.getPlannedMonthlyAmount());
            if (t.getPlannedMonths() == null) {
                json.writeNullField("planned_months");
            } else {
                json.writeNumberField("planned_months", t.getPlannedMonths());
            }
            if (t.getLinkedAccount() == null) {
                json.writeNullField("linked_account_id");
            } else {
                json.writeNumberField("linked_account_id", t.getLinkedAccount().getId());
            }
            if (t.getLendingTransactionId() == null) {
                json.writeNullField("lending_transaction_id");
            } else {
                json.writeNumberField("lending_transaction_id", t.getLendingTransactionId());
            }
            json.writeStringField("note", t.getNote());
            json.writeStringField("closed_at", t.getClosedAt() == null ? null : t.getClosedAt().toString());
            writeInstant(json, "created_at", t.getCreatedAt());
            writeInstant(json, "updated_at", t.getUpdatedAt());
            json.writeEndObject();
        }
        json.writeEndArray();
    }
}
