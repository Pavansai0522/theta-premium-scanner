package com.premiumscanner.exception;

import java.util.List;

/** Base for all expected failures. Carries the HTTP status and a stable machine-readable code. */
public class ScannerException extends RuntimeException {

    private final int status;
    private final String code;
    private final List<String> details;

    public ScannerException(int status, String code, String message) {
        this(status, code, message, List.of(), null);
    }

    public ScannerException(int status, String code, String message, List<String> details, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.code = code;
        this.details = details == null ? List.of() : List.copyOf(details);
    }

    public int getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    public List<String> getDetails() {
        return details;
    }
}
