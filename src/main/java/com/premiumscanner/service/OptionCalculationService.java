package com.premiumscanner.service;

import com.premiumscanner.domain.OptionContract;
import com.premiumscanner.domain.OptionQuote;
import com.premiumscanner.domain.OptionSnapshot;
import com.premiumscanner.domain.StrangleMetrics;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;

/** Pure option math. All money is BigDecimal; ratios and Greeks are double. */
@Service
public class OptionCalculationService {

    public static final ZoneId MARKET_ZONE = ZoneId.of("America/New_York");
    public static final BigDecimal CONTRACT_MULTIPLIER = BigDecimal.valueOf(100);
    /** After this time on expiration day a contract no longer trades; treat it as expired. */
    static final LocalTime EXPIRATION_CUTOFF = LocalTime.of(16, 15);
    private static final double DAYS_PER_YEAR = 365.0;

    private final Clock clock;

    public OptionCalculationService(Clock clock) {
        this.clock = clock;
    }

    public ZonedDateTime nowInMarket() {
        return ZonedDateTime.now(clock).withZoneSameInstant(MARKET_ZONE);
    }

    public LocalDate marketToday() {
        return nowInMarket().toLocalDate();
    }

    /** Calendar days from today (exchange time) to expiration. 0 = expires today. */
    public int daysToExpiration(LocalDate expiration) {
        return (int) ChronoUnit.DAYS.between(marketToday(), expiration);
    }

    public boolean isExpired(LocalDate expiration) {
        ZonedDateTime now = nowInMarket();
        LocalDate today = now.toLocalDate();
        return today.isAfter(expiration)
                || today.isEqual(expiration) && !now.toLocalTime().isBefore(EXPIRATION_CUTOFF);
    }

    /** OTM call: strike > spot. OTM put: strike < spot. At-the-money is not OTM. */
    public boolean isOutOfTheMoney(OptionContract contract, BigDecimal spot) {
        int cmp = contract.strike().compareTo(spot);
        return contract.isCall() ? cmp > 0 : cmp < 0;
    }

    /** ITM call: strike < spot. ITM put: strike > spot. */
    public boolean isInTheMoney(OptionContract contract, BigDecimal spot) {
        int cmp = contract.strike().compareTo(spot);
        return contract.isCall() ? cmp < 0 : cmp > 0;
    }

    /** Mid = (bid + ask) / 2, or null when the quote is one-sided or crossed. */
    public BigDecimal midPrice(BigDecimal bid, BigDecimal ask) {
        return OptionQuote.of(bid, ask, null, null, null).mid();
    }

    public BigDecimal spread(BigDecimal bid, BigDecimal ask) {
        return OptionQuote.of(bid, ask, null, null, null).spread();
    }

    /** Total strangle credit per share = call mid + put mid. */
    public BigDecimal totalPremium(BigDecimal callMid, BigDecimal putMid) {
        return callMid.add(putMid).setScale(4, RoundingMode.HALF_UP);
    }

    /** One contract covers 100 shares. */
    public BigDecimal premiumPerContract(BigDecimal totalPremium) {
        return totalPremium.multiply(CONTRACT_MULTIPLIER).setScale(2, RoundingMode.HALF_UP);
    }

    public BigDecimal lowerBreakeven(BigDecimal putStrike, BigDecimal totalPremium) {
        return putStrike.subtract(totalPremium);
    }

    public BigDecimal upperBreakeven(BigDecimal callStrike, BigDecimal totalPremium) {
        return callStrike.add(totalPremium);
    }

    /** (call strike - spot) / spot */
    public double callDistancePct(BigDecimal callStrike, BigDecimal spot) {
        return callStrike.subtract(spot).divide(spot, 8, RoundingMode.HALF_UP).doubleValue();
    }

    /** (spot - put strike) / spot */
    public double putDistancePct(BigDecimal putStrike, BigDecimal spot) {
        return spot.subtract(putStrike).divide(spot, 8, RoundingMode.HALF_UP).doubleValue();
    }

    /**
     * One-standard-deviation expected move to expiration: S * IV * sqrt(DTE / 365).
     * 0DTE uses one day so the value stays meaningful. Returns null without a valid IV.
     */
    public Double expectedMove(BigDecimal spot, Double impliedVolatility, int dte) {
        if (impliedVolatility == null || !(impliedVolatility > 0) || spot == null || spot.signum() <= 0) return null;
        double years = Math.max(dte, 1) / DAYS_PER_YEAR;
        return spot.doubleValue() * impliedVolatility * Math.sqrt(years);
    }

    /**
     * All pair-level metrics. Both legs must have a valid two-sided quote plus delta and theta
     * (guaranteed by {@link OptionFilterService}).
     *
     * @param referenceIv IV used for the expected move (at-the-money IV of the expiration when known);
     *                    falls back to the average of the two legs' IVs
     */
    public StrangleMetrics strangleMetrics(OptionSnapshot put, OptionSnapshot call, BigDecimal spot, Double referenceIv) {
        BigDecimal putMid = put.quote().mid();
        BigDecimal callMid = call.quote().mid();
        BigDecimal total = totalPremium(callMid, putMid);
        BigDecimal naturalCredit = put.quote().bid().add(call.quote().bid());
        BigDecimal lower = lowerBreakeven(put.strike(), total);
        BigDecimal upper = upperBreakeven(call.strike(), total);

        double s = spot.doubleValue();
        double lowerDist = (s - lower.doubleValue()) / s;
        double upperDist = (upper.doubleValue() - s) / s;

        Double iv = referenceIv != null && referenceIv > 0 ? referenceIv : averageIv(put, call);
        int dte = call.dte();
        Double em = expectedMove(spot, iv, dte);
        Double putCushion = em == null ? null : (s - lower.doubleValue()) / em;
        Double callCushion = em == null ? null : (upper.doubleValue() - s) / em;

                double callDelta = call.greeks().delta();
                double putDelta = put.greeks().delta();
                double pop = round4(clamp01(1.0 - Math.abs(callDelta) - Math.abs(putDelta)));
                double thetaPerDay = round2((Math.abs(call.greeks().theta()) + Math.abs(put.greeks().theta()))
                * CONTRACT_MULTIPLIER.doubleValue());

                Double gamma = call.greeks().gamma() != null && put.greeks().gamma() != null
                    ? round4(-(call.greeks().gamma() + put.greeks().gamma())) : null;
                Double vega = call.greeks().vega() != null && put.greeks().vega() != null
                    ? round4(-(call.greeks().vega() + put.greeks().vega())) : null;
        return new StrangleMetrics(call.contract().underlying(), spot, call.contract().expiration(), dte,
                putMid, callMid, total, naturalCredit, premiumPerContract(total), lower, upper,
                putDistancePct(put.strike(), spot), callDistancePct(call.strike(), spot), lowerDist, upperDist,
                iv, em, putCushion, callCushion, pop, thetaPerDay, round4(-(callDelta + putDelta)), gamma, vega);    }

    static Double averageIv(OptionSnapshot a, OptionSnapshot b) {
        Double x = a.greeks().impliedVolatility();
        Double y = b.greeks().impliedVolatility();
        if (x != null && y != null) return (x + y) / 2.0;
        return x != null ? x : y;
    }

       static double clamp01(double v) {
        if (Double.isNaN(v)) return 0;
        return Math.max(0, Math.min(1, v));
    }

    /** Removes binary floating-point noise (0.7491000000000001 -> 0.7491) from derived Greeks. */
    static double round4(double v) {
        return Math.round(v * 10_000.0) / 10_000.0;
    }

    static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}

