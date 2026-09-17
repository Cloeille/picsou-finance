package com.picsou.export;

import com.fasterxml.jackson.core.JsonGenerator;
import com.picsou.model.Account;
import com.picsou.model.AppUser;
import com.picsou.model.Goal;
import com.picsou.model.GoalAllocation;
import com.picsou.model.GoalContributor;
import com.picsou.model.GoalManualContribution;
import com.picsou.model.GoalMonthOverride;
import com.picsou.repository.GoalContributorRepository;
import com.picsou.repository.GoalManualContributionRepository;
import com.picsou.repository.GoalMonthOverrideRepository;
import com.picsou.repository.GoalRepository;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;

import static com.picsou.export.AccountsExporter.writeBigDecimal;
import static com.picsou.export.ProfileExporter.writeInstant;

@Component
class GoalsExporter implements EntityExporter {

    private final GoalRepository goalRepository;
    private final GoalContributorRepository contributorRepository;
    private final GoalManualContributionRepository manualContributionRepository;
    private final GoalMonthOverrideRepository monthOverrideRepository;

    GoalsExporter(
        GoalRepository goalRepository,
        GoalContributorRepository contributorRepository,
        GoalManualContributionRepository manualContributionRepository,
        GoalMonthOverrideRepository monthOverrideRepository
    ) {
        this.goalRepository = goalRepository;
        this.contributorRepository = contributorRepository;
        this.manualContributionRepository = manualContributionRepository;
        this.monthOverrideRepository = monthOverrideRepository;
    }

    @Override
    public String name() {
        return "goals";
    }

    @Override
    public List<String> csvHeader() {
        // Flat CSV: one row per goal with its scalar fields. Sub-collections
        // (contributors, manual contributions, month overrides) are nested only
        // in the JSON view — CSV consumers can join via goal_id from the other
        // entity CSVs we ship as part of a fuller export later.
        return List.of("id", "name", "type", "target_amount", "deadline", "monthly_amount",
            "expected_return", "start_date", "end_date", "account_ids", "created_at", "updated_at");
    }

    @Override
    public void writeCsv(AppUser user, ExportContext ctx, CsvWriter csv) throws IOException {
        for (Goal g : goalRepository.findAllByMemberIdOrderByCreatedAtAsc(user.getMember().getId())) {
            String accountIds = g.getAccounts() == null ? "" : g.getAccounts().stream()
                .map(Account::getId).map(String::valueOf).reduce((a, b) -> a + ";" + b).orElse("");
            csv.writeRow(List.of(
                String.valueOf(g.getId()),
                g.getName() == null ? "" : g.getName(),
                g.getType() == null ? "" : g.getType().name(),
                g.getTargetAmount() == null ? "" : g.getTargetAmount().toPlainString(),
                g.getDeadline() == null ? "" : g.getDeadline().toString(),
                g.getMonthlyAmount() == null ? "" : g.getMonthlyAmount().toPlainString(),
                g.getExpectedReturn() == null ? "" : g.getExpectedReturn().toPlainString(),
                g.getStartDate() == null ? "" : g.getStartDate().toString(),
                g.getEndDate() == null ? "" : g.getEndDate().toString(),
                accountIds,
                g.getCreatedAt() == null ? "" : g.getCreatedAt().toString(),
                g.getUpdatedAt() == null ? "" : g.getUpdatedAt().toString()
            ));
        }
    }

    @Override
    public void writeJson(AppUser user, ExportContext ctx, JsonGenerator json) throws IOException {
        json.writeStartArray();
        for (Goal g : goalRepository.findAllByMemberIdOrderByCreatedAtAsc(user.getMember().getId())) {
            json.writeStartObject();
            json.writeNumberField("id", g.getId());
            json.writeStringField("name", g.getName());
            json.writeStringField("type", g.getType() == null ? null : g.getType().name());
            writeBigDecimal(json, "target_amount", g.getTargetAmount());
            json.writeStringField("deadline", g.getDeadline() == null ? null : g.getDeadline().toString());
            writeBigDecimal(json, "monthly_amount", g.getMonthlyAmount());
            writeBigDecimal(json, "expected_return", g.getExpectedReturn());
            json.writeStringField("start_date", g.getStartDate() == null ? null : g.getStartDate().toString());
            json.writeStringField("end_date", g.getEndDate() == null ? null : g.getEndDate().toString());
            writeInstant(json, "created_at", g.getCreatedAt());
            writeInstant(json, "updated_at", g.getUpdatedAt());

            json.writeArrayFieldStart("account_ids");
            if (g.getAccounts() != null) {
                for (Account a : g.getAccounts()) json.writeNumber(a.getId());
            }
            json.writeEndArray();

            json.writeArrayFieldStart("contributors");
            for (GoalContributor c : contributorRepository.findByGoalId(g.getId())) {
                json.writeStartObject();
                json.writeNumberField("member_id", c.getId().getMemberId());
                json.writeEndObject();
            }
            json.writeEndArray();

            json.writeArrayFieldStart("manual_contributions");
            for (GoalManualContribution m : manualContributionRepository.findByGoalId(g.getId())) {
                json.writeStartObject();
                json.writeNumberField("id", m.getId());
                json.writeStringField("year_month", m.getYearMonth());
                writeBigDecimal(json, "amount", m.getAmount());
                if (m.getMember() != null) json.writeNumberField("member_id", m.getMember().getId());
                json.writeEndObject();
            }
            json.writeEndArray();

            // The recurring plan's monthly split. Nested in the JSON view only: the CSV header
            // above is positional, and a consumer's file misaligns silently if it changes.
            json.writeArrayFieldStart("allocations");
            if (g.getAllocations() != null) {
                for (GoalAllocation a : g.getAllocations()) {
                    json.writeStartObject();
                    json.writeNumberField("id", a.getId());
                    json.writeStringField("ticker", a.getTicker());
                    writeBigDecimal(json, "monthly_amount", a.getMonthlyAmount());
                    json.writeEndObject();
                }
            }
            json.writeEndArray();

            json.writeArrayFieldStart("month_overrides");
            for (GoalMonthOverride mo : monthOverrideRepository.findByGoalId(g.getId())) {
                json.writeStartObject();
                json.writeNumberField("id", mo.getId());
                json.writeStringField("year_month", mo.getYearMonth());
                writeBigDecimal(json, "amount", mo.getAmount());
                json.writeEndObject();
            }
            json.writeEndArray();

            json.writeEndObject();
        }
        json.writeEndArray();
    }
}
