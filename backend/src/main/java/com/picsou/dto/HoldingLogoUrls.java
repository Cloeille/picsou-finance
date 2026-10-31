package com.picsou.dto;

/**
 * Where the browser loads a holding's mark from.
 *
 * @param light the mark, or null when the holding has none
 * @param dark  a variant drawn for a dark background, or null to use {@code light} everywhere
 */
public record HoldingLogoUrls(String light, String dark) {

    public static HoldingLogoUrls of(String light) {
        return new HoldingLogoUrls(light, null);
    }
}
