package com.picsou.export;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.picsou.model.AppUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Streams a ZIP archive containing the user's complete data graph.
 *
 * Each {@link EntityExporter} contributes one CSV entry plus one section of
 * the combined {@code data.json} file. Output is written directly to the
 * given {@link OutputStream}, never buffered in full — the export of a
 * large account history must not require equivalent heap.
 *
 * Errors mid-stream cannot change the HTTP status (headers are flushed
 * before the first ZIP byte). When that happens we add an
 * {@code __EXPORT_FAILED__.txt} entry to the archive and rethrow so the
 * controller logs it.
 */
@Service
public class DataExportService {

    private static final Logger log = LoggerFactory.getLogger(DataExportService.class);
    private static final JsonFactory JSON_FACTORY = new JsonFactory()
        .disable(JsonGenerator.Feature.AUTO_CLOSE_TARGET);

    private final List<EntityExporter> exporters;

    public DataExportService(List<EntityExporter> exporters) {
        this.exporters = exporters;
    }

    @Transactional(readOnly = true)
    public void export(AppUser user, ExportContext ctx, OutputStream out) throws IOException {
        Instant exportedAt = Instant.now();
        log.info("export.start userId={} exporters={} includeBalanceSnapshots={}",
            user.getId(), exporters.size(), ctx.includeBalanceSnapshots());
        try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            try {
                writeJson(zip, user, ctx, exportedAt);
                writeCsvEntries(zip, user, ctx);
                writeReadme(zip, user, exportedAt);
                log.info("export.completed userId={}", user.getId());
            } catch (RuntimeException | IOException ex) {
                log.error("export.failed userId={}", user.getId(), ex);
                writeFailureMarker(zip);
                throw ex;
            }
        }
    }

    private void writeJson(ZipOutputStream zip, AppUser user, ExportContext ctx, Instant exportedAt) throws IOException {
        zip.putNextEntry(new ZipEntry("data.json"));
        try (JsonGenerator json = JSON_FACTORY.createGenerator(zip)) {
            json.setPrettyPrinter(new DefaultPrettyPrinter());
            json.writeStartObject();
            json.writeStringField("schema_version", "1");
            json.writeStringField("exported_at", exportedAt.toString());
            json.writeNumberField("user_id", user.getId());
            json.writeBooleanField("include_balance_snapshots", ctx.includeBalanceSnapshots());
            for (EntityExporter exporter : exporters) {
                if (!exporter.enabled(ctx)) continue;
                json.writeFieldName(exporter.name());
                exporter.writeJson(user, ctx, json);
            }
            json.writeEndObject();
            json.flush();
        }
        zip.closeEntry();
    }

    private void writeCsvEntries(ZipOutputStream zip, AppUser user, ExportContext ctx) throws IOException {
        for (EntityExporter exporter : exporters) {
            if (!exporter.enabled(ctx)) continue;
            zip.putNextEntry(new ZipEntry(exporter.name() + ".csv"));
            CsvWriter csv = new CsvWriter(zip);
            csv.writeRow(exporter.csvHeader());
            exporter.writeCsv(user, ctx, csv);
            csv.flush();
            zip.closeEntry();
        }
    }

    private void writeReadme(ZipOutputStream zip, AppUser user, Instant exportedAt) throws IOException {
        zip.putNextEntry(new ZipEntry("README.txt"));
        String body = """
            Picsou data export
            ==================

            Exported at:   %s
            User id:       %d
            Username:      %s

            Files
            -----
            data.json       — complete export, machine-readable
            *.csv           — per-entity tables, spreadsheet-friendly (UTF-8 with BOM)
            README.txt      — this file

            What is excluded
            ----------------
            For your own security, the following are deliberately NOT exported:
              - your password hash and activation token
              - your TOTP secret and MFA recovery codes
              - bank-connection refresh/access tokens and encrypted credentials
              - long-lived session tokens

            All other data we hold for your account is included in this archive
            (GDPR Art. 15 — right of access).
            """.formatted(exportedAt, user.getId(), user.getUsername());
        zip.write(body.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private void writeFailureMarker(ZipOutputStream zip) {
        try {
            zip.putNextEntry(new ZipEntry("__EXPORT_FAILED__.txt"));
            zip.write(("Export failed mid-stream — the archive is incomplete. "
                + "Please retry; if the problem persists, contact the administrator.\n")
                .getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        } catch (IOException ex) {
            // Best-effort — the zip stream is usually already broken by the original
            // failure. Log it so an operator can tell the archive carries no marker.
            log.warn("export.failure-marker-write-failed — archive is incomplete AND unmarked", ex);
        }
    }
}
