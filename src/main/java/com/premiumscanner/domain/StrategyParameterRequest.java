package com.premiumscanner.domain;

import java.math.BigDecimal;

/**
 * Raw, optional user input (query string or form). Null means "use the configured default".
 * Resolved and validated into {@link StrategyParameters}.
 */
public record StrategyParameterRequest(Double minDelta, Double maxDelta, Double minTheta,
                                       Integer minDte, Integer maxDte,
                                       BigDecimal minPremium, BigDecimal maxSpread,
                                       Long minOpenInterest, Long minVolume,
                                       BigDecimal minTotalPremium, Integer topN) {

    public static StrategyParameterRequest empty() {
        return new StrategyParameterRequest(null, null, null, null, null, null, null, null, null, null, null);
    }
}
