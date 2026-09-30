package com.premiumscanner.domain;

import java.math.BigDecimal;
import java.time.Instant;

/** Current underlying price and where it came from (latest trade, quote midpoint or daily bar close). */
public record UnderlyingQuote(String symbol, BigDecimal price, Instant timestamp, String source) {
}
