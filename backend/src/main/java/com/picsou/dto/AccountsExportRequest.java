package com.picsou.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

/**
 * Request for the per-account spreadsheet export.
 *
 * @param accountIds the accounts to put in the workbook, one sheet each. Every id is resolved
 *                   through the member-scoped read path, so an id outside the caller's
 *                   perimeter fails the request rather than leaking a sheet.
 * @param labels     column and section headings, keyed by {@link com.picsou.export.xlsx.LabelKey}
 *                   name. Supplied by the client because the backend carries no message bundle
 *                   — see the ADR. May be null or partial: every key has an English default, so
 *                   the endpoint stays usable from curl or the MCP server.
 */
public record AccountsExportRequest(
    @NotEmpty
    @Size(max = 200, message = "cannot export more than 200 accounts at once")
    List<Long> accountIds,

    Map<String, String> labels
) {
    public Map<String, String> labelsOrEmpty() {
        return labels == null ? Map.of() : labels;
    }
}
