package com.premiumscanner.service;

import com.premiumscanner.config.ScannerProperties;
import com.premiumscanner.domain.LegFilterResult;
import com.premiumscanner.domain.OptionDataset;
import com.premiumscanner.domain.OptionQuery;
import com.premiumscanner.domain.OptionSnapshot;
import com.premiumscanner.domain.OptionType;
import com.premiumscanner.domain.StrangleCandidate;
import com.premiumscanner.domain.StrangleMetrics;
import com.premiumscanner.domain.StrangleScanResult;
import com.premiumscanner.domain.StrategyParameterRequest;
import com.premiumscanner.domain.StrategyParameters;
import com.premiumscanner.domain.UnderlyingQuote;
import com.premiumscanner.exception.NoOptionsAvailableException;
import com.premiumscanner.service.StrangleRankingService.StranglePair;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Strategy orchestration: fetch OTM legs -> filter -> pair within each expiration -> rank -> top N.
 * Only OTM strikes are requested from Alpaca (calls at/above spot, puts at/below spot), which roughly
 * halves the data pulled compared with the full chain.
 */
@Service
public class StrangleService {

    private final AlpacaOptionService marketData;
    private final OptionFilterService filter;
    private final OptionCalculationService calc;
    private final StrangleRankingService ranking;
    private final ParameterResolver parameters;
    private final ScannerProperties props;

    public StrangleService(AlpacaOptionService marketData, OptionFilterService filter, OptionCalculationService calc,
                           StrangleRankingService ranking, ParameterResolver parameters, ScannerProperties props) {
        this.marketData = marketData;
        this.filter = filter;
        this.calc = calc;
        this.ranking = ranking;
        this.parameters = parameters;
        this.props = props;
    }

    public StrangleScanResult scan(String rawSymbol, StrategyParameterRequest request) {
        String symbol = SymbolValidator.normalize(rawSymbol);
        StrategyParameters p = parameters.resolve(request);
        UnderlyingQuote underlying = marketData.underlyingQuote(symbol);
        BigDecimal spot = underlying.price();

        LocalDate today = calc.marketToday();
        LocalDate from = today.plusDays(p.minDte());
        LocalDate to = today.plusDays(p.maxDte());
        OptionDataset calls = marketData.optionData(symbol, new OptionQuery(from, to, spot, null, OptionType.CALL));
        OptionDataset puts = marketData.optionData(symbol, new OptionQuery(from, to, null, spot, OptionType.PUT));
        if (calls.isEmpty() && puts.isEmpty()) {
            throw new NoOptionsAvailableException(symbol, "between " + p.minDte() + " and " + p.maxDte() + " DTE");
        }
        List<OptionSnapshot> all = new ArrayList<>(calls.options());
        all.addAll(puts.options());

        return scan(symbol, underlying, all, p, mergeWarnings(calls, puts));
    }

    /** Pure pipeline over already-fetched data (used directly by tests). */
    public StrangleScanResult scan(String symbol, UnderlyingQuote underlying, List<OptionSnapshot> options,
                                   StrategyParameters p, List<String> upstreamWarnings) {
        BigDecimal spot = underlying.price();
        LegFilterResult legs = filter.filter(options, spot, p);
        Map<LocalDate, Double> atmIv = atTheMoneyIv(options, spot);

        List<StranglePair> pairs = new ArrayList<>();
        int evaluated = 0;
        Map<LocalDate, List<OptionSnapshot>> callsByExpiration = byExpiration(legs.eligibleCalls());
        Map<LocalDate, List<OptionSnapshot>> putsByExpiration = byExpiration(legs.eligiblePuts());
        for (Map.Entry<LocalDate, List<OptionSnapshot>> entry : callsByExpiration.entrySet()) {
            List<OptionSnapshot> expPuts = putsByExpiration.get(entry.getKey());
            if (expPuts == null) continue;
            List<OptionSnapshot> bestCalls = bestLegs(entry.getValue());
            List<OptionSnapshot> bestPuts = bestLegs(expPuts);
            Double referenceIv = atmIv.get(entry.getKey());
            for (OptionSnapshot call : bestCalls) {
                for (OptionSnapshot put : bestPuts) {
                    evaluated++;
                    StrangleMetrics m = calc.strangleMetrics(put, call, spot, referenceIv);
                    if (m.totalPremium().compareTo(p.minTotalPremium()) < 0) continue;
                    pairs.add(new StranglePair(put, call, m));
                }
            }
        }
        List<StrangleCandidate> ranked = ranking.rank(pairs, p);
        Map<LocalDate, Integer> perExpiration = new HashMap<>();
        List<StrangleCandidate> top = new ArrayList<>();
        for (StrangleCandidate c : ranked) {
            if (top.size() >= p.topN()) break;
            if (perExpiration.merge(c.metrics().expiration(), 1, Integer::sum) <= props.pairing().maxPerExpiration()) {
                 top.add(c.withRank(top.size() + 1));
            }
        }

        List<String> warnings = new ArrayList<>(upstreamWarnings);
        if (marketData != null && "indicative".equalsIgnoreCase(marketData.feed())) {
            warnings.add("Using Alpaca's free 'indicative' options feed: quotes are delayed/derived, not live OPRA. "
                    + "Verify prices in your broker before acting.");
        }
        if (p.minDte() == 0) warnings.add("0DTE contracts are included: gamma risk is extreme and quotes move fast.");
        if (top.isEmpty()) {
            warnings.add("No call/put pair met every criterion. Check the rejection breakdown and widen the delta range, "
                    + "lower the theta or liquidity minimums, or extend the DTE window.");
        }

        return new StrangleScanResult(symbol, spot, underlying.timestamp(),
                marketData == null ? null : marketData.feed(), p,
                legs.callsEvaluated(), legs.putsEvaluated(),
                legs.eligibleCalls().size(), legs.eligiblePuts().size(), evaluated,
                legs.callRejections(), legs.putRejections(), List.copyOf(top),
                legs.eligibleCalls(), legs.eligiblePuts(), warnings, Instant.now());
    }

    /**
     * Pre-selects the strongest legs per side before pairing (bounded O(k^2) per expiration).
     * Quality = mid premium weighted by liquidity, so a thin, wide market cannot crowd out a tradable one.
     */
    private List<OptionSnapshot> bestLegs(List<OptionSnapshot> legs) {
        int limit = props.pairing().maxLegsPerSide();
        if (legs.size() <= limit) return legs;
        return legs.stream()
                .sorted(Comparator.comparingDouble((OptionSnapshot o) ->
                        o.quote().mid().doubleValue() * (0.25 + ranking.legLiquidity(o))).reversed())
                .limit(limit)
                .toList();
    }

    /**
     * IV closest to the money for each expiration (nearest call at/above spot and nearest put at/below spot,
     * averaged). OTM wings carry skew, so ATM IV is the better input for the expected move.
     */
    static Map<LocalDate, Double> atTheMoneyIv(List<OptionSnapshot> options, BigDecimal spot) {
        Map<LocalDate, OptionSnapshot> nearestCall = new HashMap<>();
        Map<LocalDate, OptionSnapshot> nearestPut = new HashMap<>();
        for (OptionSnapshot o : options) {
            if (o.greeks().impliedVolatility() == null) continue;
            LocalDate exp = o.contract().expiration();
            if (o.type() == OptionType.CALL && o.strike().compareTo(spot) >= 0) {
                nearestCall.merge(exp, o, (a, b) -> a.strike().compareTo(b.strike()) <= 0 ? a : b);
            } else if (o.type() == OptionType.PUT && o.strike().compareTo(spot) <= 0) {
                nearestPut.merge(exp, o, (a, b) -> a.strike().compareTo(b.strike()) >= 0 ? a : b);
            }
        }
        Map<LocalDate, Double> result = new HashMap<>();
        for (LocalDate exp : new LinkedHashSet<>(concat(nearestCall.keySet(), nearestPut.keySet()))) {
            OptionSnapshot c = nearestCall.get(exp);
            OptionSnapshot p = nearestPut.get(exp);
            Double iv = c != null && p != null
                    ? (c.greeks().impliedVolatility() + p.greeks().impliedVolatility()) / 2.0
                    : (c != null ? c.greeks().impliedVolatility() : p.greeks().impliedVolatility());
            result.put(exp, iv);
        }
        return result;
    }

    private static Map<LocalDate, List<OptionSnapshot>> byExpiration(List<OptionSnapshot> legs) {
        return legs.stream().collect(Collectors.groupingBy(o -> o.contract().expiration(), TreeMap::new, Collectors.toList()));
    }

    private static List<String> mergeWarnings(OptionDataset a, OptionDataset b) {
        LinkedHashSet<String> set = new LinkedHashSet<>(a.warnings());
        set.addAll(b.warnings());
        return new ArrayList<>(set);
    }

    private static <T> List<T> concat(java.util.Collection<T> a, java.util.Collection<T> b) {
        List<T> list = new ArrayList<>(a);
        list.addAll(b);
        return list;
    }
}
