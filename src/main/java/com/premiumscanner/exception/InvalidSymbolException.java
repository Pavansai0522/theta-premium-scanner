package com.premiumscanner.exception;

public class InvalidSymbolException extends ScannerException {
    public InvalidSymbolException(String symbol, String reason) {
        super(404, "INVALID_SYMBOL", "Unknown or invalid symbol '" + symbol + "': " + reason);
    }
}
