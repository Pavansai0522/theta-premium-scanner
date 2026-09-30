package com.premiumscanner.domain;

/**
 * Greeks as returned by Alpaca. Any value may be null: Alpaca omits Greeks/IV when it lacks a valid
 * two-sided quote, an underlying trade or a solvable IV. Values are per share, per the Alpaca docs;
 * theta is the change in option value per calendar day (negative for a long option).
 */
public record OptionGreeks(Double delta, Double gamma, Double theta, Double vega, Double rho, Double impliedVolatility) {

    public static final OptionGreeks MISSING = new OptionGreeks(null, null, null, null, null, null);

    public static OptionGreeks sanitized(Double delta, Double gamma, Double theta, Double vega, Double rho, Double iv) {
        return new OptionGreeks(finite(delta), finite(gamma), finite(theta), finite(vega), finite(rho),
                iv != null && Double.isFinite(iv) && iv > 0 ? iv : null);
    }

    /** Delta and theta are the minimum required for strategy selection. */
    public boolean hasDeltaAndTheta() {
        return delta != null && theta != null;
    }

    public boolean anyPresent() {
        return delta != null || gamma != null || theta != null || vega != null || impliedVolatility != null;
    }

    private static Double finite(Double v) {
        return v != null && Double.isFinite(v) ? v : null;
    }
}
