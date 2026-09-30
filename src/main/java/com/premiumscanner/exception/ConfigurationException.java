package com.premiumscanner.exception;

public class ConfigurationException extends ScannerException {
    public ConfigurationException(String message) {
        super(503, "ALPACA_NOT_CONFIGURED", message);
    }
}
