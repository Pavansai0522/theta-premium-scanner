package com.premiumscanner.domain;

import com.premiumscanner.exception.InvalidParameterException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Fully resolved scanner parameters. Delta bounds are absolute values applied to both sides:
 * calls need {@code minDelta <= delta <= maxDelta}, puts need {@code -maxDelta <= delta <= -minDelta}.
 * {@code minTheta} is compared against |theta| (daily decay per share).
 * {@code minPremium} and {@code maxSpread} apply per leg; {@code minTotalPremium} to the combined credit.
 */
public record StrategyParameters(double minDelta, double maxDelta, double minTheta,
                                 int minDte, int maxDte,
                                 BigDecimal minPremium, BigDecimal maxSpread,
                                 long minOpenInterest, long minVolume,
                                 BigDecimal minTotalPremium, int topN) {

    public static final int MAX_DTE_LIMIT = 1095;
    public static final int MAX_TOP_N = 200;

    public StrategyParameters validate() {
        List<String> errors = new ArrayList<>();
        if (!(minDelta > 0 && minDelta < 1)) errors.add("minDelta must be between 0 and 1 (exclusive), got " + minDelta);
        if (!(maxDelta > 0 && maxDelta < 1)) errors.add("maxDelta must be between 0 and 1 (exclusive), got " + maxDelta);
        if (minDelta > maxDelta) errors.add("minDelta (" + minDelta + ") must not exceed maxDelta (" + maxDelta + ")");
        if (maxDelta >= 0.5) errors.add("maxDelta must be below 0.50 so both legs stay out of the money, got " + maxDelta);
        if (!(minTheta >= 0) || Double.isInfinite(minTheta)) errors.add("minTheta must be zero or positive (it is compared to |theta|), got " + minTheta);
        if (minDte < 0) errors.add("minDte must be zero or positive, got " + minDte);
        if (maxDte < minDte) errors.add("maxDte (" + maxDte + ") must be greater than or equal to minDte (" + minDte + ")");
        if (maxDte > MAX_DTE_LIMIT) errors.add("maxDte must be at most " + MAX_DTE_LIMIT + ", got " + maxDte);
        if (minPremium == null || minPremium.signum() < 0) errors.add("minPremium must be zero or positive");
        if (maxSpread == null || maxSpread.signum() <= 0) errors.add("maxSpread must be greater than zero");
        if (minOpenInterest < 0) errors.add("minOpenInterest must be zero or positive");
        if (minVolume < 0) errors.add("minVolume must be zero or positive");
        if (minTotalPremium == null || minTotalPremium.signum() < 0) errors.add("minTotalPremium must be zero or positive");
        if (topN < 1 || topN > MAX_TOP_N) errors.add("topN must be between 1 and " + MAX_TOP_N + ", got " + topN);
        if (!errors.isEmpty()) {
            throw new InvalidParameterException(errors);
        }
        return this;
    }

    /** DTE the ranking treats as ideal: the middle of the user's window. */
    public double targetDte() {
        return (minDte + maxDte) / 2.0;
    }
}
