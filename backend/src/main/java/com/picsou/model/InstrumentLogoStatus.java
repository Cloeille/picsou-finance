package com.picsou.model;

/** What the last logo lookup for a ticker concluded. See {@code V104__instrument_logo.sql}. */
public enum InstrumentLogoStatus {
    /** The mark is stored and served. */
    STORED,
    /** The source answered and has no usable mark. Permanent: never asked again. */
    ABSENT,
    /** The source did not answer. Asked again once the retry delay has passed. */
    FAILED
}
