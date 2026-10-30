package com.picsou.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * {@code update} is null when there is nothing to show. The app serialises with
 * {@code non_null}; the field is forced so the contract's explicit {@code "update": null} is honoured.
 */
public record InstanceStatusResponse(
    String currentVersion,
    @JsonInclude(JsonInclude.Include.ALWAYS) ReleaseUpdate update,
    List<SidecarIssue> issues
) {}
