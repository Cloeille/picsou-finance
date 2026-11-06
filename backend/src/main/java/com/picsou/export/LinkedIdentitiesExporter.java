package com.picsou.export;

import com.fasterxml.jackson.core.JsonGenerator;
import com.picsou.model.AppUser;
import com.picsou.model.UserIdentity;
import com.picsou.repository.UserIdentityRepository;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;

import static com.picsou.export.ProfileExporter.nullSafe;
import static com.picsou.export.ProfileExporter.writeInstant;

/** The exporting user's own SSO identities. Runs whether or not SSO is configured: rows outlive the setting. */
@Component
class LinkedIdentitiesExporter implements EntityExporter {

    private final UserIdentityRepository identityRepository;

    LinkedIdentitiesExporter(UserIdentityRepository identityRepository) {
        this.identityRepository = identityRepository;
    }

    @Override
    public String name() {
        return "linked_identities";
    }

    @Override
    public List<String> csvHeader() {
        return List.of("id", "issuer", "subject", "email", "display_name", "created_at", "last_login_at");
    }

    @Override
    public void writeCsv(AppUser user, ExportContext ctx, CsvWriter csv) throws IOException {
        for (UserIdentity i : identityRepository.findByUserIdOrderByCreatedAtAsc(user.getId())) {
            csv.writeRow(List.of(
                String.valueOf(i.getId()),
                nullSafe(i.getIssuer()),
                nullSafe(i.getSubject()),
                nullSafe(i.getEmail()),
                nullSafe(i.getDisplayName()),
                i.getCreatedAt() == null ? "" : i.getCreatedAt().toString(),
                i.getLastLoginAt() == null ? "" : i.getLastLoginAt().toString()
            ));
        }
    }

    @Override
    public void writeJson(AppUser user, ExportContext ctx, JsonGenerator json) throws IOException {
        json.writeStartArray();
        for (UserIdentity i : identityRepository.findByUserIdOrderByCreatedAtAsc(user.getId())) {
            json.writeStartObject();
            json.writeNumberField("id", i.getId());
            json.writeStringField("issuer", i.getIssuer());
            json.writeStringField("subject", i.getSubject());
            json.writeStringField("email", i.getEmail());
            json.writeStringField("display_name", i.getDisplayName());
            writeInstant(json, "created_at", i.getCreatedAt());
            writeInstant(json, "last_login_at", i.getLastLoginAt());
            json.writeEndObject();
        }
        json.writeEndArray();
    }
}
