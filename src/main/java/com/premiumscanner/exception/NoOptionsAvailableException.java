package com.premiumscanner.exception;

public class NoOptionsAvailableException extends ScannerException {
    public NoOptionsAvailableException(String symbol, String scope) {
        super(404, "NO_OPTIONS_AVAILABLE", "No option contracts returned for " + symbol + " " + scope);
    }
}
