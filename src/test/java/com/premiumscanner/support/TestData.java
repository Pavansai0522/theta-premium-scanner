package com.premiumscanner.support;

import com.premiumscanner.config.ScannerProperties;
import com.premiumscanner.domain.OptionContract;
import com.premiumscanner.domain.OptionGreeks;
import com.premiumscanner.domain.OptionQuote;
import com.premiumscanner.domain.OptionSnapshot;
import com.premiumscanner.domain.OptionType;
import com.premiumscanner.domain.StrategyParameters;
import com.premiumscanner.domain.UnderlyingQuote;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** Shared fixtures. "Today" is Sunday 9 Aug 2026, 10:00 ET, so 21 Aug 2026 is 12 DTE (the spec's example). */
public final class TestData {

    public static final ZoneId ET = ZoneId.of("America/New_York");
    public static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-09T14:00:00Z"), ET);
    public static final LocalDate AUG_21 = LocalDate.of(2026, 8, 21);
    public static final LocalDate AUG_28 = LocalDate.of(2026, 8, 28);
    public static final BigDecimal SPOT = new BigDecimal("650.00");
    private static final DateTimeFormatter OCC_DATE = DateTimeFormatter.ofPattern("yyMMdd");

    private TestData() {
    }

    public static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    public static String occ(String root, LocalDate expiration, OptionType type, String strike) {
        long thousandths = new BigDecimal(strike).movePointRight(3).longValueExact();
        return root + expiration.format(OCC_DATE) + (type == OptionType.CALL ? "C" : "P") + String.format("%08d", thousandths);
    }

    public static int dte(LocalDate expiration) {
        return (int) java.time.temporal.ChronoUnit.DAYS.between(LocalDate.of(2026, 8, 9), expiration);
    }

    /** Fully specified leg. Pass null delta/theta to simulate missing Greeks. */
    public static OptionSnapshot leg(OptionType type, LocalDate expiration, String strike, String bid, String ask,
                                     Double delta, Double theta, Long openInterest, long volume, Double iv) {
        OptionContract contract = new OptionContract(occ("SPY", expiration, type, strike), "SPY", type, bd(strike), expiration);
        OptionQuote quote = OptionQuote.of(bid == null ? null : bd(bid), ask == null ? null : bd(ask), 10L, 10L,
                Instant.parse("2026-08-07T19:59:00Z"));
        OptionGreeks greeks = new OptionGreeks(delta, delta == null ? null : 0.005, theta, delta == null ? null : 0.12, 0.01, iv);
        return new OptionSnapshot(contract, quote, greeks, volume, openInterest, null, dte(expiration), List.of());
    }

    public static OptionSnapshot call(String strike, String bid, String ask, double delta, double theta) {
        return leg(OptionType.CALL, AUG_21, strike, bid, ask, delta, theta, 2_000L, 1_000, 0.18);
    }

    public static OptionSnapshot put(String strike, String bid, String ask, double delta, double theta) {
        return leg(OptionType.PUT, AUG_21, strike, bid, ask, delta, theta, 2_000L, 1_000, 0.20);
    }

    public static UnderlyingQuote spy() {
        return new UnderlyingQuote("SPY", SPOT, Instant.parse("2026-08-07T19:59:59Z"), "latest trade");
    }

    /** The spec's example parameters. */
    public static StrategyParameters params() {
        return new StrategyParameters(0.05, 0.15, 0.015, 7, 45, bd("0.20"), bd("0.20"), 500, 0, bd("0"), 25);
    }

    public static StrategyParameters params(double minDelta, double maxDelta, double minTheta) {
        return new StrategyParameters(minDelta, maxDelta, minTheta, 7, 45, bd("0.20"), bd("0.20"), 500, 0, bd("0"), 25);
    }

    public static ScannerProperties properties() {
        return new ScannerProperties(
                new ScannerProperties.Defaults(0.05, 0.15, 0.015, 7, 45, bd("0.20"), bd("0.20"), 500L, 0L, bd("0.00"), 25),
                null, null, null, null);
    }
}
