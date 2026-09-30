package com.premiumscanner.exception;

public class UnderlyingPriceUnavailableException extends ScannerException {
    public UnderlyingPriceUnavailableException(String symbol) {
        super(502, "UNDERLYING_PRICE_UNAVAILABLE",
                "Alpaca returned no usable trade, quote or bar price for " + symbol + "; OTM status cannot be determined");
    }
}
