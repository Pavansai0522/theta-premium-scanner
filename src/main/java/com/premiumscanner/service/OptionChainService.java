package com.premiumscanner.service;

import com.premiumscanner.config.ScannerProperties;
import com.premiumscanner.domain.ExpirationGroup;
import com.premiumscanner.domain.OptionChain;
import com.premiumscanner.domain.OptionDataset;
import com.premiumscanner.domain.OptionQuery;
import com.premiumscanner.domain.OptionSnapshot;
import com.premiumscanner.domain.OptionType;
import com.premiumscanner.domain.StrikeRow;
import com.premiumscanner.domain.UnderlyingQuote;
import com.premiumscanner.exception.InvalidParameterException;
import com.premiumscanner.exception.NoOptionsAvailableException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Builds the two-sided (Calls | Strike | Puts) chain grouped by expiration. */
@Service
public class OptionChainService {

    private static final int MULTIPLIER = 100;

    private final AlpacaOptionService marketData;
    private final OptionCalculationService calc;
    private final ScannerProperties props;

    public OptionChainService(AlpacaOptionService marketData, OptionCalculationService calc, ScannerProperties props) {
        this.marketData = marketData;
        this.calc = calc;
        this.props = props;
    }

    /**
     * @param strikeWindowPct strikes within +/- this fraction of spot (0 = every strike). Keeps the default view
     *                        readable; the full chain is one query parameter away.
     */
    public OptionChain chain(String rawSymbol, Integer minDte, Integer maxDte, Double strikeWindowPct) {
        String symbol = SymbolValidator.normalize(rawSymbol);
        int from = minDte == null ? 0 : minDte;
        int to = maxDte == null ? props.chain().defaultMaxDte() : maxDte;
        double window = strikeWindowPct == null ? props.chain().strikeWindowPct() : strikeWindowPct;
        List<String> errors = new ArrayList<>();
        if (from < 0) errors.add("minDte must be zero or positive");
        if (to < from) errors.add("maxDte must be greater than or equal to minDte");
        if (to > 1095) errors.add("maxDte must be at most 1095");
        if (!(window >= 0 && window <= 1)) errors.add("strikeWindowPct must be between 0 and 1 (0 = all strikes)");
        if (!errors.isEmpty()) throw new InvalidParameterException(errors);

        UnderlyingQuote underlying = marketData.underlyingQuote(symbol);
        BigDecimal spot = underlying.price();
        LocalDate today = calc.marketToday();
        BigDecimal strikeFrom = null;
        BigDecimal strikeTo = null;
        if (window > 0) {
            strikeFrom = spot.multiply(BigDecimal.valueOf(1 - window)).setScale(2, RoundingMode.FLOOR);
            strikeTo = spot.multiply(BigDecimal.valueOf(1 + window)).setScale(2, RoundingMode.CEILING);
        }
        OptionQuery query = new OptionQuery(today.plusDays(from), today.plusDays(to), strikeFrom, strikeTo, null);
        OptionDataset data = marketData.optionData(symbol, query);
        if (data.isEmpty()) {
            throw new NoOptionsAvailableException(symbol, "between " + from + " and " + to + " DTE"
                    + (window > 0 ? " within " + Math.round(window * 100) + "% of spot" : ""));
        }
        return buildChain(symbol, underlying, data, marketData.feed());
    }

    public OptionChain buildChain(String symbol, UnderlyingQuote underlying, OptionDataset data, String feed) {
        BigDecimal spot = underlying.price();
        Map<LocalDate, TreeMap<BigDecimal, OptionSnapshot[]>> byExpiration = new TreeMap<>();
        int missingGreeks = 0;
        for (OptionSnapshot o : data.options()) {
            OptionSnapshot[] pair = byExpiration
                    .computeIfAbsent(o.contract().expiration(), k -> new TreeMap<>())
                    .computeIfAbsent(o.strike(), k -> new OptionSnapshot[2]);
            pair[o.type() == OptionType.CALL ? 0 : 1] = o;
            if (!o.greeks().hasDeltaAndTheta()) missingGreeks++;
        }

        List<ExpirationGroup> groups = new ArrayList<>();
        byExpiration.forEach((expiration, strikes) -> {
            List<StrikeRow> rows = new ArrayList<>(strikes.size());
            int calls = 0;
            int puts = 0;
            int missing = 0;
            int spotIndex = -1;
            for (Map.Entry<BigDecimal, OptionSnapshot[]> e : strikes.entrySet()) {
                OptionSnapshot call = e.getValue()[0];
                OptionSnapshot put = e.getValue()[1];
                if (call != null) calls++;
                if (put != null) puts++;
                if (call != null && !call.greeks().hasDeltaAndTheta()) missing++;
                if (put != null && !put.greeks().hasDeltaAndTheta()) missing++;
                if (spotIndex < 0 && e.getKey().compareTo(spot) > 0) spotIndex = rows.size();
                rows.add(new StrikeRow(e.getKey(), call, put,
                        call != null && calc.isInTheMoney(call.contract(), spot),
                        put != null && calc.isInTheMoney(put.contract(), spot)));
            }
            groups.add(new ExpirationGroup(expiration, calc.daysToExpiration(expiration), isMonthly(expiration),
                    MULTIPLIER, rows, spotIndex < 0 ? rows.size() : spotIndex, calls, puts, missing));
        });

        List<String> warnings = new ArrayList<>(data.warnings());
        if (missingGreeks > 0) {
            warnings.add(missingGreeks + " contract(s) have no Delta/Theta from Alpaca; they are shown in the chain but excluded from the scanner");
        }
        return new OptionChain(symbol, spot, underlying.timestamp(), underlying.source(), feed, Instant.now(),
                data.options().size(), missingGreeks, groups, warnings);
    }

    /** Standard monthly expiration = third Friday of the month. Everything else is labelled weekly (W). */
    static boolean isMonthly(LocalDate date) {
        return date.getDayOfWeek() == DayOfWeek.FRIDAY && (date.getDayOfMonth() - 1) / 7 == 2;
    }
}
