package com.picsou.export;

import com.fasterxml.jackson.core.JsonGenerator;
import com.picsou.model.AppUser;
import com.picsou.model.PropertyValuation;
import com.picsou.repository.PropertyValuationRepository;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;

import static com.picsou.export.AccountsExporter.writeBigDecimal;
import static com.picsou.export.ProfileExporter.nullSafe;
import static com.picsou.export.ProfileExporter.writeInstant;

/**
 * Exports the property valuation history.
 *
 * <p>{@code method_detail} is included deliberately: it is what makes an old estimate
 * explainable after the heuristics have changed, and an export that dropped it would hand
 * back numbers nobody could account for.
 */
@Component
class PropertyValuationsExporter implements EntityExporter {

    private final PropertyValuationRepository valuationRepository;

    PropertyValuationsExporter(PropertyValuationRepository valuationRepository) {
        this.valuationRepository = valuationRepository;
    }

    @Override
    public String name() {
        return "property_valuations";
    }

    @Override
    public List<String> csvHeader() {
        return List.of(
            "id", "account_id", "valued_at", "estimated_value", "low_value", "high_value",
            "price_per_sqm", "provider", "confidence", "sample_size", "source_year",
            "method_detail", "created_at", "updated_at"
        );
    }

    @Override
    public void writeCsv(AppUser user, ExportContext ctx, CsvWriter csv) throws IOException {
        for (PropertyValuation v : rows(user)) {
            csv.writeRow(List.of(
                String.valueOf(v.getId()),
                String.valueOf(v.getAccount().getId()),
                v.getValuedAt() == null ? "" : v.getValuedAt().toString(),
                v.getEstimatedValue() == null ? "" : v.getEstimatedValue().toPlainString(),
                v.getLowValue() == null ? "" : v.getLowValue().toPlainString(),
                v.getHighValue() == null ? "" : v.getHighValue().toPlainString(),
                v.getPricePerSqm() == null ? "" : v.getPricePerSqm().toPlainString(),
                nullSafe(v.getProvider()),
                v.getConfidence() == null ? "" : v.getConfidence().name(),
                v.getSampleSize() == null ? "" : String.valueOf(v.getSampleSize()),
                v.getSourceYear() == null ? "" : String.valueOf(v.getSourceYear()),
                nullSafe(v.getMethodDetail()),
                v.getCreatedAt() == null ? "" : v.getCreatedAt().toString(),
                v.getUpdatedAt() == null ? "" : v.getUpdatedAt().toString()
            ));
        }
    }

    @Override
    public void writeJson(AppUser user, ExportContext ctx, JsonGenerator json) throws IOException {
        json.writeStartArray();
        for (PropertyValuation v : rows(user)) {
            json.writeStartObject();
            json.writeNumberField("id", v.getId());
            json.writeNumberField("account_id", v.getAccount().getId());
            json.writeStringField("valued_at", v.getValuedAt() == null ? null : v.getValuedAt().toString());
            writeBigDecimal(json, "estimated_value", v.getEstimatedValue());
            writeBigDecimal(json, "low_value", v.getLowValue());
            writeBigDecimal(json, "high_value", v.getHighValue());
            writeBigDecimal(json, "price_per_sqm", v.getPricePerSqm());
            json.writeStringField("provider", v.getProvider());
            json.writeStringField("confidence", v.getConfidence() == null ? null : v.getConfidence().name());
            if (v.getSampleSize() != null) {
                json.writeNumberField("sample_size", v.getSampleSize());
            } else {
                json.writeNullField("sample_size");
            }
            if (v.getSourceYear() != null) {
                json.writeNumberField("source_year", v.getSourceYear());
            } else {
                json.writeNullField("source_year");
            }
            json.writeStringField("method_detail", v.getMethodDetail());
            writeInstant(json, "created_at", v.getCreatedAt());
            writeInstant(json, "updated_at", v.getUpdatedAt());
            json.writeEndObject();
        }
        json.writeEndArray();
    }

    private List<PropertyValuation> rows(AppUser user) {
        return valuationRepository.findByMemberId(user.getMember().getId());
    }
}
