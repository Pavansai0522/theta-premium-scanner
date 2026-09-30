package com.premiumscanner.exception;

import java.util.List;

public class InvalidParameterException extends ScannerException {
    public InvalidParameterException(List<String> errors) {
        super(400, "INVALID_PARAMETERS", "Invalid parameters: " + String.join("; ", errors), errors, null);
    }

    public InvalidParameterException(String error) {
        this(List.of(error));
    }
}
