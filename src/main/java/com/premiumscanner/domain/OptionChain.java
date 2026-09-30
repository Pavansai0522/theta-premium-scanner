package com.premiumscanner.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record OptionChain(String symbol, BigDecimal underlyingPrice, Instant underlyingPriceTime,
                          String underlyingPriceSource, String feed, Instant fetchedAt,
                          int totalContracts, int contractsMissingGreeks,
                          List<ExpirationGroup> expirations, List<String> warnings) {

    public OptionChain {
        expirations = List.copyOf(expirations);
        warnings = List.copyOf(warnings);
    }
}
