package com.premiumscanner.service;

import com.premiumscanner.domain.LegFilterResult;
import com.premiumscanner.domain.OptionSnapshot;
import com.premiumscanner.domain.OptionType;
import com.premiumscanner.domain.RejectionReason;
import com.premiumscanner.domain.StrategyParameters;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Leg-level screening. Each leg is checked in {@link RejectionReason} order and the first failure is
 * recorded, so the UI can show exactly why the funnel narrowed.
 */
@Service
public class OptionFilterService {

    private static final double EPS = 1e-9;

    private final OptionCalculationService calc;

    public OptionFilterService(OptionCalculationService calc) {
        this.calc = calc;
    }

    public LegFilterResult filter(List<OptionSnapshot> options, BigDecimal spot, StrategyParameters p) {
        List<OptionSnapshot> calls = new ArrayList<>();
        List<OptionSnapshot> puts = new ArrayList<>();
        Map<RejectionReason, Integer> callRejections = new EnumMap<>(RejectionReason.class);
        Map<RejectionReason, Integer> putRejections = new EnumMap<>(RejectionReason.class);
        int callsEvaluated = 0;
        int putsEvaluated = 0;
        for (OptionSnapshot o : options) {
            boolean isCall = o.type() == OptionType.CALL;
            if (isCall) callsEvaluated++; else putsEvaluated++;
            Optional<RejectionReason> reason = rejectionReason(o, spot, p);
            if (reason.isPresent()) {
                (isCall ? callRejections : putRejections).merge(reason.get(), 1, Integer::sum);
            } else {
                (isCall ? calls : puts).add(o);
            }
        }
        return new LegFilterResult(calls, puts, callRejections, putRejections, callsEvaluated, putsEvaluated);
    }

    /** Empty when the leg qualifies as a short strangle leg. */
    public Optional<RejectionReason> rejectionReason(OptionSnapshot o, BigDecimal spot, StrategyParameters p) {
        if (o.dte() < 0 || calc.isExpired(o.contract().expiration())) return Optional.of(RejectionReason.EXPIRED);
        if (o.dte() < p.minDte() || o.dte() > p.maxDte()) return Optional.of(RejectionReason.DTE_OUT_OF_RANGE);
        if (!calc.isOutOfTheMoney(o.contract(), spot)) return Optional.of(RejectionReason.NOT_OUT_OF_THE_MONEY);
        if (!o.quote().hasAsk()) return Optional.of(RejectionReason.MISSING_QUOTE);
        if (!o.quote().hasBid()) return Optional.of(RejectionReason.ZERO_BID);
        if (o.quote().crossed() || o.quote().mid() == null) return Optional.of(RejectionReason.INVALID_QUOTE);
        if (!o.greeks().hasDeltaAndTheta()) return Optional.of(RejectionReason.MISSING_GREEKS);
        if (!greeksConsistent(o)) return Optional.of(RejectionReason.INVALID_GREEKS);

        double absDelta = Math.abs(o.greeks().delta());
        if (absDelta < p.minDelta() - EPS || absDelta > p.maxDelta() + EPS) return Optional.of(RejectionReason.DELTA_OUT_OF_RANGE);
        if (Math.abs(o.greeks().theta()) < p.minTheta() - EPS) return Optional.of(RejectionReason.THETA_BELOW_MIN);
        if (o.quote().spread().compareTo(p.maxSpread()) > 0) return Optional.of(RejectionReason.SPREAD_TOO_WIDE);
        if (o.quote().mid().compareTo(p.minPremium()) < 0) return Optional.of(RejectionReason.PREMIUM_TOO_LOW);
        if (p.minOpenInterest() > 0 && o.openInterest() == null) return Optional.of(RejectionReason.OPEN_INTEREST_UNAVAILABLE);
        if (o.openInterest() != null && o.openInterest() < p.minOpenInterest()) return Optional.of(RejectionReason.OPEN_INTEREST_TOO_LOW);
        if (o.volume() < p.minVolume()) return Optional.of(RejectionReason.VOLUME_TOO_LOW);
        return Optional.empty();
    }

    /**
     * Call delta must be in [0, 1], put delta in [-1, 0]. Theta for an OTM option should be negative
     * (time decay); a positive value signals a bad model output, so the leg is not trusted.
     */
    static boolean greeksConsistent(OptionSnapshot o) {
        double delta = o.greeks().delta();
        boolean deltaOk = o.type() == OptionType.CALL ? delta >= 0 && delta <= 1 : delta <= 0 && delta >= -1;
        return deltaOk && o.greeks().theta() <= 0;
    }
}
