package com.premiumscanner.service;

import com.premiumscanner.exception.InvalidParameterException;

import java.util.Locale;
import java.util.regex.Pattern;

public final class SymbolValidator {

    private static final Pattern VALID = Pattern.compile("^[A-Z][A-Z0-9.]{0,9}$");

    private SymbolValidator() {
    }

    /** Trims and upper-cases; rejects anything that cannot be a US ticker before we spend an API call on it. */
    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new InvalidParameterException("symbol is required");
        }
        String symbol = raw.trim().toUpperCase(Locale.ROOT);
        if (!VALID.matcher(symbol).matches()) {
            throw new InvalidParameterException("symbol '" + raw.trim() + "' is not a valid ticker (letters, digits and '.', max 10 chars)");
        }
        return symbol;
    }
}
