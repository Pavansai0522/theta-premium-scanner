package com.premiumscanner.domain;

public enum OptionType {
    CALL, PUT;

    public static OptionType fromOccCode(char code) {
        return switch (code) {
            case 'C' -> CALL;
            case 'P' -> PUT;
            default -> throw new IllegalArgumentException("Unknown OCC option type: " + code);
        };
    }

    /** Value Alpaca expects for the {@code type} query parameter. */
    public String alpacaValue() {
        return name().toLowerCase();
    }
}
