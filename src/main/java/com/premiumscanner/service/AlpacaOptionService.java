package com.premiumscanner.service;

import com.premiumscanner.client.AlpacaClient;
import com.premiumscanner.client.dto.BarDto;
import com.premiumscanner.client.dto.GreeksDto;
import com.premiumscanner.client.dto.OptionContractDto;
import com.premiumscanner.client.dto.OptionSnapshotDto;
import com.premiumscanner.client.dto.QuoteDto;
import com.premiumscanner.client.dto.StockSnapshotDto;
import com.premiumscanner.client.dto.TradeDto;
import com.premiumscanner.config.AlpacaProperties;
import com.premiumscanner.domain.OptionContract;
import com.premiumscanner.domain.OptionDataset;
import com.premiumscanner.domain.OptionGreeks;
import com.premiumscanner.domain.OptionQuery;
import com.premiumscanner.domain.OptionQuote;
import com.premiumscanner.domain.OptionSnapshot;
import com.premiumscanner.domain.UnderlyingQuote;
import com.premiumscanner.exception.AlpacaApiException;
import com.premiumscanner.exception.InvalidSymbolException;
import com.premiumscanner.exception.PaginationException;
import com.premiumscanner.exception.UnderlyingPriceUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Fetches raw Alpaca data and normalises it into domain objects ("Option Processor" in the architecture).
 * Handles missing/invalid values contract-by-contract so one bad row never fails the whole chain.
 * A short TTL cache keeps the chain view and the scanner on the same snapshot and protects the rate limit.
 */
@Service
public class AlpacaOptionService {

    private static final Logger log = LoggerFactory.getLogger(AlpacaOptionService.class);
    private static final int MAX_CACHE_ENTRIES = 256;

    private final AlpacaClient client;
    private final OptionCalculationService calc;
    private final AlpacaProperties props;
    private final Clock clock;
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    private record CacheEntry(Instant storedAt, Object value) {
    }

    public AlpacaOptionService(AlpacaClient client, OptionCalculationService calc, AlpacaProperties props, Clock clock) {
        this.client = client;
        this.calc = calc;
        this.props = props;
        this.clock = clock;
    }

    public String feed() {
        return props.optionsFeed();
    }

    public UnderlyingQuote underlyingQuote(String symbol) {
        return cached("underlying|" + symbol, () -> loadUnderlying(symbol));
    }

    public OptionDataset optionData(String symbol, OptionQuery query) {
        return cached("options|" + symbol + "|" + query.cacheKey(), () -> loadOptions(symbol, query));
    }

    // ------------------------------------------------------------------ underlying

    private UnderlyingQuote loadUnderlying(String symbol) {
        StockSnapshotDto snapshot;
        try {
            snapshot = client.getStockSnapshot(symbol);
        } catch (AlpacaApiException e) {
            int s = e.getUpstreamStatus();
            if (s == 400 || s == 404 || s == 422) {
                throw new InvalidSymbolException(symbol, "Alpaca has no market data for this ticker");
            }
            throw e;
        }
        if (snapshot == null) {
            throw new InvalidSymbolException(symbol, "Alpaca returned an empty snapshot");
        }
        return selectUnderlyingPrice(symbol, snapshot);
    }

    /**
     * Picks the freshest valid price: latest trade vs. quote midpoint (whichever is newer), then the
     * daily bar close, then the previous close. Stale-but-present beats nothing, but the source and
     * timestamp are surfaced so the user can judge.
     */
    static UnderlyingQuote selectUnderlyingPrice(String symbol, StockSnapshotDto s) {
        UnderlyingQuote trade = null;
        TradeDto t = s.latestTrade();
        if (t != null && isPositive(t.price())) {
            trade = new UnderlyingQuote(symbol, money(t.price()), parseInstant(t.timestamp()), "latest trade");
        }
        UnderlyingQuote quoteMid = null;
        QuoteDto q = s.latestQuote();
        if (q != null && isPositive(q.bidPrice()) && isPositive(q.askPrice()) && q.askPrice() >= q.bidPrice()) {
            BigDecimal mid = money(q.bidPrice()).add(money(q.askPrice())).divide(BigDecimal.valueOf(2), 4, RoundingMode.HALF_UP);
            quoteMid = new UnderlyingQuote(symbol, mid, parseInstant(q.timestamp()), "quote midpoint");
        }
        UnderlyingQuote live = newest(trade, quoteMid);
        if (live != null) return live;
        for (BarDto bar : new BarDto[]{s.dailyBar(), s.prevDailyBar()}) {
            if (bar != null && isPositive(bar.close())) {
                return new UnderlyingQuote(symbol, money(bar.close()), parseInstant(bar.timestamp()),
                        bar == s.dailyBar() ? "daily bar close" : "previous close");
            }
        }
        throw new UnderlyingPriceUnavailableException(symbol);
    }

    private static UnderlyingQuote newest(UnderlyingQuote a, UnderlyingQuote b) {
        if (a == null) return b;
        if (b == null) return a;
        if (a.timestamp() == null) return b.timestamp() == null ? a : b;
        if (b.timestamp() == null) return a;
        return b.timestamp().isAfter(a.timestamp()) ? b : a;
    }

    // ------------------------------------------------------------------ options

    private OptionDataset loadOptions(String symbol, OptionQuery query) {
        Map<String, OptionSnapshotDto> raw;
        try {
            raw = client.getOptionSnapshots(symbol, query);
        } catch (AlpacaApiException e) {
            int s = e.getUpstreamStatus();
            if (s == 404 || s == 422) {
                throw new InvalidSymbolException(symbol, "Alpaca has no option chain for this ticker");
            }
            throw e;
        }
        List<String> warnings = new ArrayList<>();
        if (raw.isEmpty()) {
            return new OptionDataset(List.of(), warnings, 0, 0);
        }
        Map<String, Long> openInterest = loadOpenInterest(symbol, query, warnings);

        List<OptionSnapshot> options = new ArrayList<>(raw.size());
        int expired = 0;
        int unparseable = 0;
        for (Map.Entry<String, OptionSnapshotDto> entry : raw.entrySet()) {
            Optional<OptionContract> contract = OccSymbolParser.parse(entry.getKey(), symbol);
            if (contract.isEmpty()) {
                unparseable++;
                continue;
            }
            if (calc.isExpired(contract.get().expiration())) {
                expired++;
                continue;
            }
            options.add(toSnapshot(contract.get(), entry.getValue(), openInterest));
        }
        options.sort(Comparator.comparing((OptionSnapshot o) -> o.contract().expiration())
                .thenComparing(o -> o.contract().type())
                .thenComparing(OptionSnapshot::strike));
        if (expired > 0) warnings.add(expired + " expired contract(s) were ignored");
        if (unparseable > 0) warnings.add(unparseable + " contract(s) had unrecognised symbols and were ignored");
        log.info("{}: {} option snapshots ({} expired dropped, {} unparseable)", symbol, options.size(), expired, unparseable);
        return new OptionDataset(options, warnings, expired, unparseable);
    }

    /** Open interest lives on the Trading API. If it is unavailable we degrade and say so rather than fail. */
    private Map<String, Long> loadOpenInterest(String symbol, OptionQuery query, List<String> warnings) {
        try {
            Map<String, Long> oi = new HashMap<>();
            for (OptionContractDto c : client.getOptionContracts(symbol, query)) {
                Long value = parseLong(c.openInterest());
                if (c.symbol() != null && value != null && value >= 0) oi.put(c.symbol(), value);
            }
            return oi;
        } catch (AlpacaApiException | PaginationException e) {
            log.warn("Open interest unavailable for {}: {}", symbol, e.getMessage());
            warnings.add("Open interest unavailable (" + e.getMessage() + "). Legs are rejected when a minimum open interest is set.");
            return Map.of();
        }
    }

    OptionSnapshot toSnapshot(OptionContract contract, OptionSnapshotDto dto, Map<String, Long> openInterest) {
        List<String> issues = new ArrayList<>();
        QuoteDto q = dto == null ? null : dto.latestQuote();
        OptionQuote quote;
        if (q == null) {
            quote = OptionQuote.EMPTY;
            issues.add("No quote");
        } else {
            BigDecimal bid = money(q.bidPrice());
            BigDecimal ask = money(q.askPrice());
            if (bid != null && bid.signum() < 0) {
                issues.add("Negative bid ignored");
                bid = null;
            }
            if (ask != null && ask.signum() < 0) {
                issues.add("Negative ask ignored");
                ask = null;
            }
            quote = OptionQuote.of(bid, ask, q.bidSize(), q.askSize(), parseInstant(q.timestamp()));
            if (!quote.hasBid()) issues.add("No bid");
            if (!quote.hasAsk()) issues.add("No ask");
            if (quote.crossed()) issues.add("Crossed quote (ask < bid)");
        }

        GreeksDto g = dto == null ? null : dto.greeks();
        Double iv = dto == null ? null : dto.impliedVolatility();
        OptionGreeks greeks = g == null
                ? OptionGreeks.sanitized(null, null, null, null, null, iv)
                : OptionGreeks.sanitized(g.delta(), g.gamma(), g.theta(), g.vega(), g.rho(), iv);
        if (!greeks.hasDeltaAndTheta()) issues.add("Greeks unavailable");
        if (greeks.impliedVolatility() == null) issues.add("IV unavailable");

        BarDto bar = dto == null ? null : dto.dailyBar();
        long volume = bar != null && bar.volume() != null ? Math.max(0, bar.volume()) : 0;
        TradeDto trade = dto == null ? null : dto.latestTrade();
        BigDecimal last = trade == null ? null : money(trade.price());

        return new OptionSnapshot(contract, quote, greeks, volume, openInterest.get(contract.symbol()), last,
                calc.daysToExpiration(contract.expiration()), issues);
    }

    // ------------------------------------------------------------------ helpers

    @SuppressWarnings("unchecked")
    private <T> T cached(String key, Supplier<T> loader) {
        Duration ttl = props.cacheTtl();
        if (ttl.isZero() || ttl.isNegative()) return loader.get();
        Instant now = clock.instant();
        CacheEntry hit = cache.get(key);
        if (hit != null && hit.storedAt().plus(ttl).isAfter(now)) {
            return (T) hit.value();
        }
        T value = loader.get();
        if (cache.size() >= MAX_CACHE_ENTRIES) {
            cache.entrySet().removeIf(e -> !e.getValue().storedAt().plus(ttl).isAfter(now));
            if (cache.size() >= MAX_CACHE_ENTRIES) cache.clear();
        }
        cache.put(key, new CacheEntry(now, value));
        return value;
    }

    static BigDecimal money(Double value) {
        if (value == null || !Double.isFinite(value)) return null;
        return BigDecimal.valueOf(value);
    }

    private static boolean isPositive(Double v) {
        return v != null && Double.isFinite(v) && v > 0;
    }

    static Instant parseInstant(String timestamp) {
        if (timestamp == null || timestamp.isBlank()) return null;
        try {
            return Instant.parse(timestamp);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static Long parseLong(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return new BigDecimal(value.trim()).longValue();
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
