package com.picsou.export;

import com.fasterxml.jackson.core.JsonGenerator;
import com.picsou.model.AppUser;
import com.picsou.model.PeerCounterparty;
import com.picsou.repository.PeerCounterpartyRepository;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;

import static com.picsou.export.ProfileExporter.nullSafe;
import static com.picsou.export.ProfileExporter.writeInstant;

/**
 * The people a member lent money to. It is the member's own address book: the borrower
 * is an outside person, not a household member, so it stays in the export.
 */
@Component
class PeerCounterpartiesExporter implements EntityExporter {

    private final PeerCounterpartyRepository counterpartyRepository;

    PeerCounterpartiesExporter(PeerCounterpartyRepository counterpartyRepository) {
        this.counterpartyRepository = counterpartyRepository;
    }

    @Override
    public String name() {
        return "peer_counterparties";
    }

    @Override
    public List<String> csvHeader() {
        return List.of("id", "member_id", "name", "note", "created_at", "updated_at");
    }

    @Override
    public void writeCsv(AppUser user, ExportContext ctx, CsvWriter csv) throws IOException {
        for (PeerCounterparty c : counterpartyRepository.findAllByMemberIdOrderByNameAsc(user.getMember().getId())) {
            csv.writeRow(List.of(
                String.valueOf(c.getId()),
                String.valueOf(c.getMember().getId()),
                nullSafe(c.getName()),
                nullSafe(c.getNote()),
                c.getCreatedAt() == null ? "" : c.getCreatedAt().toString(),
                c.getUpdatedAt() == null ? "" : c.getUpdatedAt().toString()
            ));
        }
    }

    @Override
    public void writeJson(AppUser user, ExportContext ctx, JsonGenerator json) throws IOException {
        json.writeStartArray();
        for (PeerCounterparty c : counterpartyRepository.findAllByMemberIdOrderByNameAsc(user.getMember().getId())) {
            json.writeStartObject();
            json.writeNumberField("id", c.getId());
            json.writeNumberField("member_id", c.getMember().getId());
            json.writeStringField("name", c.getName());
            json.writeStringField("note", c.getNote());
            writeInstant(json, "created_at", c.getCreatedAt());
            writeInstant(json, "updated_at", c.getUpdatedAt());
            json.writeEndObject();
        }
        json.writeEndArray();
    }
}
