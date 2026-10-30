package com.picsou.model;

/**
 * French savings book product types.
 * <ul>
 *   <li>LIVRET_A – regulated, rate set by government decree, always net of tax (tax-exempt)</li>
 *   <li>LDDS    – regulated, same rate as Livret A, tax-exempt</li>
 *   <li>LEP     – regulated, rate set by decree (usually higher than Livret A), tax-exempt</li>
 *   <li>COMMERCIAL – bank-specific livret; rate is user-supplied, may be gross or net of PFU</li>
 * </ul>
 * Values must match the {@code savings_product} PostgreSQL enum defined in V44.
 */
public enum SavingsProduct {
    LIVRET_A,
    LDDS,
    LEP,
    COMMERCIAL
}
