package com.picsou.model;

/**
 * Indicates whether an annual savings rate is expressed gross (before tax) or net (after tax).
 * <p>
 * Regulated products (LIVRET_A, LDDS, LEP) are always NET — the {@code rate_basis} column
 * is ignored for them and only meaningful for {@code COMMERCIAL} livrets.
 * </p>
 * Values must match the {@code rate_basis} PostgreSQL enum defined in V44.
 */
public enum RateBasis {
    GROSS,
    NET
}
