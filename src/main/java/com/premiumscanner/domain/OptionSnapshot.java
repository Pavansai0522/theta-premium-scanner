package com.premiumscanner.domain;

import java.math.BigDecimal;
import java.util.List;

/**
 * One option contract with its market data at a point in time.
 *
 * @param volume       session volume from the daily bar (0 when Alpaca returns no bar)
 * @param openInterest open interest from the Alpaca contracts endpoint; null when unavailable
 * @param dte          calendar days to expiration in exchange time
 * @param dataIssues   human-readable data-quality notes (missing Greeks, no bid, crossed quote...)
 */
public record OptionSnapshot(OptionContract contract, OptionQuote quote, OptionGreeks greeks,
                             long volume, Long openInterest, BigDecimal lastPrice, int dte,
                             List<String> dataIssues) {

    public OptionSnapshot {
        quote = quote == null ? OptionQuote.EMPTY : quote;
        greeks = greeks == null ? OptionGreeks.MISSING : greeks;
        dataIssues = dataIssues == null ? List.of() : List.copyOf(dataIssues);
    }

    public String symbol() {
        return contract.symbol();
    }

    public BigDecimal strike() {
        return contract.strike();
    }

    public OptionType type() {
        return contract.type();
    }

    public Double absDelta() {
        return greeks.delta() == null ? null : Math.abs(greeks.delta());
    }

    public Double absTheta() {
        return greeks.theta() == null ? null : Math.abs(greeks.theta());
    }
}
