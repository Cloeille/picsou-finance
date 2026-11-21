package com.picsou.adapter;

import java.util.Locale;
import java.util.regex.Pattern;

/** The identifiers Louve Invest hands out, and which of them Picsou can store as what. */
public final class LouvePortfolioCodes {

    /** A Louve {@code scpiUrlId} is a URL slug ({@code aurore}, {@code horizon-pierre}). */
    private static final Pattern FUND_CODE = Pattern.compile("[a-z0-9][a-z0-9._-]{0,39}");
    private static final Pattern ISIN_SHAPE = Pattern.compile("[A-Z]{2}[A-Z0-9]{9}[0-9]");

    private LouvePortfolioCodes() {}

    /** Whether {@code code} fits {@code scpi_position.louve_fund_code} and looks like a slug. */
    public static boolean isFundCode(String code) {
        return code != null && FUND_CODE.matcher(code).matches();
    }

    /**
     * {@code isinCode} when it is a real ISIN (shape and ISO 6166 check digit), else null. Louve
     * also puts internal references there ({@code SCPI99990001}); those have the shape of an ISIN
     * but not its check digit, and are never stored as one.
     */
    public static String isinOrNull(String isinCode) {
        if (isinCode == null) {
            return null;
        }
        String candidate = isinCode.trim().toUpperCase(Locale.ROOT);
        return ISIN_SHAPE.matcher(candidate).matches() && checkDigitHolds(candidate) ? candidate : null;
    }

    /** Luhn over the digits of the first eleven characters, letters expanded to 10..35. */
    private static boolean checkDigitHolds(String isin) {
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < 11; i++) {
            digits.append(Character.digit(isin.charAt(i), 36));
        }
        int sum = 0;
        boolean twice = true;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int n = digits.charAt(i) - '0';
            if (twice) {
                n *= 2;
                if (n > 9) n -= 9;
            }
            sum += n;
            twice = !twice;
        }
        return (10 - sum % 10) % 10 == isin.charAt(11) - '0';
    }
}
