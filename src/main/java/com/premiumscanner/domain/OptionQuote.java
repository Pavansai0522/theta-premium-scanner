package com.premiumscanner.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

/**
 * NBBO-style quote for one contract. Prices are BigDecimal: premium and breakeven math is money math.
 * {@code mid} and {@code spread} are derived once in {@link #of} so every consumer (ranking, UI, JSON) agrees.
 */
public record OptionQuote(BigDecimal bid, BigDecimal ask, Long bidSize, Long askSize,
                          BigDecimal mid, BigDecimal spread, Instant timestamp) {

    private static final BigDecimal TWO = BigDecimal.valueOf(2);

    public static final OptionQuote EMPTY = new OptionQuote(null, null, null, null, null, null, null);

    public static OptionQuote of(BigDecimal bid, BigDecimal ask, Long bidSize, Long askSize, Instant timestamp) {
        BigDecimal mid = null;
        BigDecimal spread = null;
        boolean askValid = ask != null && ask.signum() > 0;
        boolean bidPresent = bid != null && bid.signum() >= 0;
        if (askValid && bidPresent && ask.compareTo(bid) >= 0) {
            mid = bid.add(ask).divide(TWO, 4, RoundingMode.HALF_UP);
            spread = ask.subtract(bid);
        }
        return new OptionQuote(bid, ask, bidSize, askSize, mid, spread, timestamp);
    }

    public boolean hasBid() {
        return bid != null && bid.signum() > 0;
    }

    public boolean hasAsk() {
        return ask != null && ask.signum() > 0;
    }

    public boolean crossed() {
        return bid != null && ask != null && ask.signum() > 0 && ask.compareTo(bid) < 0;
    }

    /** True when the quote can be sold at a positive price with a sane market (bid > 0, ask >= bid). */
    public boolean tradableForSale() {
        return hasBid() && hasAsk() && !crossed();
    }

    /** Spread as a fraction of mid (0.25 = spread is 25% of mid), or null when undefined. */
    public Double spreadPctOfMid() {
        if (mid == null || spread == null || mid.signum() <= 0) return null;
        return spread.doubleValue() / mid.doubleValue();
    }
}
