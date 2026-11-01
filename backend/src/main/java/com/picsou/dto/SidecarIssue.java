package com.picsou.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * {@code expectedBase} is null when no shared base is declared for a sidecar.
 * The app serialises with {@code non_null}; the field is forced so the contract's
 * explicit {@code "expectedBase": null} is honoured.
 */
public record SidecarIssue(
    SidecarIssueCode code,
    String sidecar,
    @JsonInclude(JsonInclude.Include.ALWAYS) String expectedBase
) {}
