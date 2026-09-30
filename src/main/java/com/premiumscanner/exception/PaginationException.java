package com.premiumscanner.exception;

public class PaginationException extends ScannerException {
    public PaginationException(String message) {
        super(502, "PAGINATION_ERROR", message);
    }
}
