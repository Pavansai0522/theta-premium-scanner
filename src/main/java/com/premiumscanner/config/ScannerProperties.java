package com.premiumscanner.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

/**
 * Scanner configuration. Strategy defaults are deliberately NOT given fallback values in code:
 * they live in application.yml and can be overridden per request.
 * Ranking weights and liquidity references are algorithm tuning knobs and have safe fallbacks.
 */
@ConfigurationProperties(prefix = "scanner")
public record ScannerProperties(Defaults defaults, Weights weights, Liquidity liquidity, Chain chain, Pairing pairing) {

    public ScannerProperties {
        defaults = defaults == null ? new Defaults(null, null, null, null, null, null, null, null, null, null, null) : defaults;
        weights = weights == null ? new Weights(null, null, null, null, null, null, null) : weights;
        liquidity = liquidity == null ? new Liquidity(null, null, null) : liquidity;
        chain = chain == null ? new Chain(null, null) : chain;
            pairing = pairing == null ? new Pairing(null, null, null) : pairing;
    }

    /** User-overridable strategy defaults (any may be null if not configured). */
    public record Defaults(Double minDelta, Double maxDelta, Double minTheta,
                           Integer minDte, Integer maxDte,
                           BigDecimal minPremium, BigDecimal maxSpread,
                           Long minOpenInterest, Long minVolume,
                           BigDecimal minTotalPremium, Integer topN) {
    }

    public record Weights(Double premium, Double theta, Double delta, Double cushion,
                          Double liquidity, Double dteFit, Double balance) {
        public Weights {
            premium = nonNegative(premium, 0.25);
            theta = nonNegative(theta, 0.15);
            delta = nonNegative(delta, 0.15);
            cushion = nonNegative(cushion, 0.20);
            liquidity = nonNegative(liquidity, 0.15);
            dteFit = nonNegative(dteFit, 0.05);
            balance = nonNegative(balance, 0.05);
        }

        public double total() {
            return premium + theta + delta + cushion + liquidity + dteFit + balance;
        }
    }

    public record Liquidity(Long openInterestReference, Long volumeReference, Double maxSpreadPctOfMid) {
        public Liquidity {
            openInterestReference = openInterestReference == null || openInterestReference < 1 ? 10_000L : openInterestReference;
            volumeReference = volumeReference == null || volumeReference < 1 ? 5_000L : volumeReference;
            maxSpreadPctOfMid = maxSpreadPctOfMid == null || maxSpreadPctOfMid <= 0 ? 0.50 : maxSpreadPctOfMid;
        }
    }

    public record Chain(Integer defaultMaxDte, Double strikeWindowPct) {
        public Chain {
            defaultMaxDte = defaultMaxDte == null || defaultMaxDte < 0 ? 45 : defaultMaxDte;
            strikeWindowPct = strikeWindowPct == null || strikeWindowPct < 0 ? 0.15 : strikeWindowPct;
        }
    }

    public record Pairing(Integer maxLegsPerSide, Double targetCushion, Integer maxPerExpiration) {
    public Pairing {
        maxLegsPerSide = maxLegsPerSide == null || maxLegsPerSide < 1 ? 25 : maxLegsPerSide;
        targetCushion = targetCushion == null || targetCushion <= 0 ? 1.25 : targetCushion;
        maxPerExpiration = maxPerExpiration == null || maxPerExpiration < 1 ? 5 : maxPerExpiration;
    }
}

    private static Double nonNegative(Double value, double fallback) {
        return value == null || value < 0 || value.isNaN() ? fallback : value;
    }
}
