package com.picsou.dto;

import com.picsou.model.CategoryKind;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CategoryRequest(
    @NotBlank @Size(max = 100) String name,
    @NotNull CategoryKind kind,
    String color,
    String icon,
    Integer sortOrder,
    /**
     * Optional parent for a sub-category. Validated server-side: the parent must belong to the
     * same member, share this {@code kind}, and be a root itself (one level of nesting only).
     * {@code null} makes this a top-level category.
     */
    Long parentId
) {}
