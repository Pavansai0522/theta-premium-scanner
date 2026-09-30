package com.premiumscanner.service;

import com.premiumscanner.config.ScannerProperties;
import com.premiumscanner.domain.OptionSnapshot;
import com.premiumscanner.domain.ScoreBreakdown;
import com.premiumscanner.domain.StrangleCandidate;
import com.premiumscanner.domain.StrangleMetrics;
import com.premiumscanner.domain.StrategyParameters;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.ToDoubleFunction;

/**
 * Multi-factor ranking. Each factor is scaled to 0..1, weighted (weights from configuration), and summed
 * into a 0..100 score. Relative factors (premium, theta, delta safety) are min-max normalised across the
 * candidate set, so the score answers "how good is this pair compared with the alternatives right now".
 * Absolute factors (cushion, liquidity, DTE fit, balance) are scored against fixed references so a
 * universally illiquid or tight set cannot look good just by comparison.
 */
@Service
public class StrangleRankingService {

    public record StranglePair(OptionSnapshot put, OptionSnapshot call, StrangleMetrics metrics) {
    }

    private final ScannerProperties props;

    public StrangleRankingService(ScannerProperties props) {
        this.props = props;
    }

    public List<StrangleCandidate> rank(List<StranglePair> pairs, StrategyParameters params) {
        if (pairs.isEmpty()) return List.of();
        Range premium = Range.of(pairs, p -> p.metrics().totalPremium().doubleValue());
        Range theta = Range.of(pairs, p -> p.metrics().thetaPerDay());
        Range safety = Range.of(pairs, p -> p.metrics().probabilityOfProfit());

        List<StrangleCandidate> scored = new ArrayList<>(pairs.size());
        for (StranglePair pair : pairs) {
            StrangleMetrics m = pair.metrics();
            ScoreBreakdown breakdown = new ScoreBreakdown(
                    premium.normalize(m.totalPremium().doubleValue()),
                    theta.normalize(m.thetaPerDay()),
                    safety.normalize(m.probabilityOfProfit()),
                    cushionScore(m),
                    pairLiquidity(pair.put(), pair.call()),
                    dteFit(m.dte(), params),
                    deltaBalance(pair.put(), pair.call()));
            scored.add(new StrangleCandidate(pair.put().symbol() + "_" + pair.call().symbol(), 0,
                    round(weightedScore(breakdown)), breakdown, m, pair.put(), pair.call(), flags(pair)));
        }
        scored.sort(Comparator.comparingDouble(StrangleCandidate::score).reversed()
                .thenComparing(c -> c.metrics().totalPremium(), Comparator.reverseOrder())
                .thenComparing(StrangleCandidate::id));
        List<StrangleCandidate> ranked = new ArrayList<>(scored.size());
        for (int i = 0; i < scored.size(); i++) ranked.add(scored.get(i).withRank(i + 1));
        return ranked;
    }

    double weightedScore(ScoreBreakdown b) {
        ScannerProperties.Weights w = props.weights();
        double total = w.total();
        if (total <= 0) return 0;
        double sum = w.premium() * b.premium() + w.theta() * b.theta() + w.delta() * b.delta()
                + w.cushion() * b.cushion() + w.liquidity() * b.liquidity() + w.dteFit() * b.dteFit()
                + w.balance() * b.balance();
        return 100.0 * sum / total;
    }

    /**
     * Distance of each breakeven from spot, measured in expected moves. The weaker side decides, and the
     * score saturates at the configured target: beyond it extra distance only costs premium, so it is not
     * rewarded further. Without IV there is no expected move, so the factor is neutral (0.5) and flagged.
     */
    double cushionScore(StrangleMetrics m) {
        if (m.putCushion() == null || m.callCushion() == null) return 0.5;
        double weakest = Math.min(m.putCushion(), m.callCushion());
        return clamp01(weakest / props.pairing().targetCushion());
    }

    /** Execution quality of the pair = its weaker leg (you have to fill both). */
    public double pairLiquidity(OptionSnapshot put, OptionSnapshot call) {
        return Math.min(legLiquidity(put), legLiquidity(call));
    }

    /** 40% spread tightness (relative to mid), 35% open interest, 25% volume, each 0..1 on a log scale. */
    public double legLiquidity(OptionSnapshot leg) {
        ScannerProperties.Liquidity ref = props.liquidity();
        Double spreadPct = leg.quote().spreadPctOfMid();
        double spreadScore = spreadPct == null ? 0 : clamp01(1.0 - spreadPct / ref.maxSpreadPctOfMid());
        double oiScore = leg.openInterest() == null ? 0 : logScore(leg.openInterest(), ref.openInterestReference());
        double volumeScore = logScore(leg.volume(), ref.volumeReference());
        return 0.40 * spreadScore + 0.35 * oiScore + 0.25 * volumeScore;
    }

    /** 1.0 at the middle of the user's DTE window, falling linearly to 0.5 at its edges. */
    double dteFit(int dte, StrategyParameters params) {
        double half = Math.max(1.0, (params.maxDte() - params.minDte()) / 2.0);
        return clamp01(1.0 - 0.5 * Math.abs(dte - params.targetDte()) / half);
    }

    /** 1.0 when |call delta| == |put delta| (delta-neutral at entry), lower as the position skews. */
    double deltaBalance(OptionSnapshot put, OptionSnapshot call) {
        double c = Math.abs(call.greeks().delta());
        double p = Math.abs(put.greeks().delta());
        double sum = c + p;
        return sum <= 0 ? 1.0 : clamp01(1.0 - Math.abs(c - p) / sum);
    }

    private List<String> flags(StranglePair pair) {
        StrangleMetrics m = pair.metrics();
        List<String> flags = new ArrayList<>();
        if (m.expectedMove() == null) flags.add("IV unavailable: cushion scored as neutral");
        if (m.dte() == 0) flags.add("0DTE: extreme gamma risk");
        else if (m.dte() <= 3) flags.add("Expires within 3 days: elevated gamma risk");
        if (m.putCushion() != null && m.putCushion() < 1) flags.add("Lower breakeven is inside 1 expected move");
        if (m.callCushion() != null && m.callCushion() < 1) flags.add("Upper breakeven is inside 1 expected move");
        addSpreadFlag(flags, "put", pair.put());
        addSpreadFlag(flags, "call", pair.call());
        if (pair.put().openInterest() == null || pair.call().openInterest() == null) flags.add("Open interest unavailable");
        return flags;
    }

    private static void addSpreadFlag(List<String> flags, String side, OptionSnapshot leg) {
        Double pct = leg.quote().spreadPctOfMid();
        if (pct != null && pct > 0.25) {
            flags.add(String.format(Locale.US, "Wide %s spread (%.0f%% of mid): expect slippage", side, pct * 100));
        }
    }

    private static double logScore(long value, long reference) {
        if (value <= 0) return 0;
        return clamp01(Math.log10(1 + value) / Math.log10(1 + reference));
    }

    static double clamp01(double v) {
        if (Double.isNaN(v)) return 0;
        return Math.max(0, Math.min(1, v));
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private record Range(double min, double max) {
        static <T> Range of(List<T> items, ToDoubleFunction<T> f) {
            double min = Double.POSITIVE_INFINITY;
            double max = Double.NEGATIVE_INFINITY;
            for (T item : items) {
                double v = f.applyAsDouble(item);
                min = Math.min(min, v);
                max = Math.max(max, v);
            }
            return new Range(min, max);
        }

        /** A factor that does not vary across candidates cannot discriminate between them: everyone gets 1. */
        double normalize(double v) {
            double width = max - min;
            if (width < 1e-12) return 1.0;
            return clamp01((v - min) / width);
        }
    }
}
