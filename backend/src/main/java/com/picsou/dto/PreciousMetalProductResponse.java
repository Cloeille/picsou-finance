package com.picsou.dto;

import com.picsou.model.PreciousMetal;

/** One entry of the precious-metals catalogue: what a holding's ticker means and counts. */
public record PreciousMetalProductResponse(String ticker, String name, PreciousMetal.Unit unit) {

    public static PreciousMetalProductResponse from(PreciousMetal metal) {
        return new PreciousMetalProductResponse(metal.ticker(), metal.displayName(), metal.unit());
    }
}
