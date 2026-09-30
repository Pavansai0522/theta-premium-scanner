package com.premiumscanner.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.premiumscanner.domain.OptionType;
import com.premiumscanner.support.TestData;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-process stand-in for Alpaca's market-data and trading APIs (JDK HttpServer, no extra dependencies).
 * Honours the same filters and pagination semantics the scanner relies on, and serves 3 options per page
 * so every scan crosses page boundaries.
 */
final class FakeAlpacaServer {

    static final int PAGE_SIZE = 3;
    private static final ObjectMapper JSON = new ObjectMapper();

    record FakeOption(OptionType type, LocalDate expiration, String strike, Double bid, Double ask,
                      Double delta, Double theta, Double iv, long openInterest, long volume) {
        String symbol() {
            return TestData.occ("SPY", expiration, type, strike);
        }
    }

    /** Spot is 650. Comments give the scanner's expected verdict with the spec's example parameters. */
    static final List<FakeOption> OPTIONS = List.of(
            // 21 Aug 2026 (12 DTE)
            new FakeOption(OptionType.CALL, TestData.AUG_21, "640", 11.0, 11.2, 0.72, -0.110, 0.17, 4000, 900),  // ITM
            new FakeOption(OptionType.CALL, TestData.AUG_21, "660", 3.10, 3.20, 0.30, -0.200, 0.16, 5000, 3000),  // delta too high
            new FakeOption(OptionType.CALL, TestData.AUG_21, "680", 0.35, 0.40, 0.08, -0.018, 0.15, 2500, 1200),  // eligible
            new FakeOption(OptionType.CALL, TestData.AUG_21, "685", 0.27, 0.31, 0.06, -0.015, 0.15, 1500, 800),   // eligible
            new FakeOption(OptionType.CALL, TestData.AUG_21, "690", 0.20, 0.24, 0.04, -0.011, 0.16, 1400, 500),   // delta too low
            new FakeOption(OptionType.CALL, TestData.AUG_21, "700", 0.05, 0.10, null, null, null, 900, 100),      // no Greeks
            new FakeOption(OptionType.PUT, TestData.AUG_21, "610", 0.00, 0.05, -0.03, -0.008, 0.26, 1000, 0),     // zero bid
            new FakeOption(OptionType.PUT, TestData.AUG_21, "620", 0.22, 0.26, -0.05, -0.014, 0.24, 2000, 900),   // theta too low
            new FakeOption(OptionType.PUT, TestData.AUG_21, "625", 0.28, 0.32, -0.07, -0.016, 0.23, 3000, 1500),  // eligible
            new FakeOption(OptionType.PUT, TestData.AUG_21, "630", 0.45, 0.95, -0.10, -0.020, 0.22, 2600, 700),   // spread too wide
            new FakeOption(OptionType.PUT, TestData.AUG_21, "660", 11.0, 11.2, -0.70, -0.100, 0.18, 3000, 600),   // ITM
            // 28 Aug 2026 (19 DTE)
            new FakeOption(OptionType.CALL, TestData.AUG_28, "690", 0.45, 0.50, 0.10, -0.022, 0.16, 1200, 400),   // eligible
            new FakeOption(OptionType.PUT, TestData.AUG_28, "615", 0.38, 0.42, -0.09, -0.021, 0.24, 1800, 650)    // eligible
    );

    private final HttpServer server;
    final AtomicInteger snapshotRequests = new AtomicInteger();

    private FakeAlpacaServer(HttpServer server) {
        this.server = server;
    }

    static FakeAlpacaServer start() {
        try {
            HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            FakeAlpacaServer fake = new FakeAlpacaServer(http);
            http.createContext("/v2/stocks/", fake::stockSnapshot);
            http.createContext("/v1beta1/options/snapshots/", fake::optionSnapshots);
            http.createContext("/v2/options/contracts", fake::contracts);
            http.start();
            return fake;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    void stop() {
        server.stop(0);
    }

    // ------------------------------------------------------------------ handlers

    private void stockSnapshot(HttpExchange ex) throws IOException {
        if (!authorised(ex)) return;
        if (!ex.getRequestURI().getPath().equals("/v2/stocks/SPY/snapshot")) {
            send(ex, 404, Map.of("message", "not found"));
            return;
        }
        send(ex, 200, Map.of(
                "symbol", "SPY",
                "latestTrade", Map.of("p", 650.00, "s", 100, "t", "2026-08-07T19:59:59Z"),
                "latestQuote", Map.of("bp", 649.90, "ap", 650.10, "bs", 1, "as", 1, "t", "2026-08-07T19:59:30Z"),
                "prevDailyBar", Map.of("c", 646.10, "v", 1000, "t", "2026-08-06T04:00:00Z")));
    }

    private void optionSnapshots(HttpExchange ex) throws IOException {
        if (!authorised(ex)) return;
        snapshotRequests.incrementAndGet();
        if (!ex.getRequestURI().getPath().endsWith("/SPY")) {
            send(ex, 200, mapOf("snapshots", Map.of(), "next_page_token", null));
            return;
        }
        Map<String, String> q = query(ex);
        List<FakeOption> matching = OPTIONS.stream().filter(o -> matches(o, q)).toList();
        int offset = q.containsKey("page_token") ? Integer.parseInt(q.get("page_token").substring("page-".length())) : 0;
        int end = Math.min(matching.size(), offset + PAGE_SIZE);
        Map<String, Object> snapshots = new LinkedHashMap<>();
        for (FakeOption o : matching.subList(offset, end)) snapshots.put(o.symbol(), snapshotJson(o));
        send(ex, 200, mapOf("snapshots", snapshots, "next_page_token", end < matching.size() ? "page-" + end : null));
    }

    private void contracts(HttpExchange ex) throws IOException {
        if (!authorised(ex)) return;
        Map<String, String> q = query(ex);
        List<Map<String, Object>> contracts = new ArrayList<>();
        for (FakeOption o : OPTIONS) {
            if (!matches(o, q)) continue;
            contracts.add(Map.of("symbol", o.symbol(), "underlying_symbol", "SPY",
                    "type", o.type().alpacaValue(), "strike_price", o.strike(),
                    "expiration_date", o.expiration().toString(), "open_interest", String.valueOf(o.openInterest()),
                    "status", "active"));
        }
        send(ex, 200, mapOf("option_contracts", contracts, "next_page_token", null));
    }

    // ------------------------------------------------------------------ helpers

    private static Map<String, Object> snapshotJson(FakeOption o) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("latestQuote", Map.of("bp", o.bid(), "ap", o.ask(), "bs", 10, "as", 10, "t", "2026-08-07T19:59:00Z"));
        if (o.delta() != null) {
            s.put("greeks", Map.of("delta", o.delta(), "theta", o.theta(), "gamma", 0.004, "vega", 0.11, "rho", 0.01));
            s.put("impliedVolatility", o.iv());
        }
        s.put("dailyBar", Map.of("c", o.ask(), "v", o.volume(), "t", "2026-08-07T04:00:00Z"));
        return s;
    }

    private static boolean matches(FakeOption o, Map<String, String> q) {
        if (q.containsKey("type") && !q.get("type").equals(o.type().alpacaValue())) return false;
        BigDecimal strike = new BigDecimal(o.strike());
        if (q.containsKey("strike_price_gte") && strike.compareTo(new BigDecimal(q.get("strike_price_gte"))) < 0) return false;
        if (q.containsKey("strike_price_lte") && strike.compareTo(new BigDecimal(q.get("strike_price_lte"))) > 0) return false;
        if (q.containsKey("expiration_date_gte") && o.expiration().isBefore(LocalDate.parse(q.get("expiration_date_gte")))) return false;
        return !q.containsKey("expiration_date_lte") || !o.expiration().isAfter(LocalDate.parse(q.get("expiration_date_lte")));
    }

    private boolean authorised(HttpExchange ex) throws IOException {
        String key = ex.getRequestHeaders().getFirst("APCA-API-KEY-ID");
        String secret = ex.getRequestHeaders().getFirst("APCA-API-SECRET-KEY");
        if ("test-key".equals(key) && "test-secret".equals(secret)) return true;
        send(ex, 401, Map.of("message", "request is not authorized"));
        return false;
    }

    private static Map<String, String> query(HttpExchange ex) {
        Map<String, String> params = new HashMap<>();
        String raw = ex.getRequestURI().getRawQuery();
        if (raw == null) return params;
        for (String pair : raw.split("&")) {
            int i = pair.indexOf('=');
            String k = URLDecoder.decode(i < 0 ? pair : pair.substring(0, i), StandardCharsets.UTF_8);
            String v = i < 0 ? "" : URLDecoder.decode(pair.substring(i + 1), StandardCharsets.UTF_8);
            params.put(k, v);
        }
        return params;
    }

    private static Map<String, Object> mapOf(String k1, Object v1, String k2, Object v2) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(k1, v1);
        m.put(k2, v2);
        return m;
    }

    private static void send(HttpExchange ex, int status, Object body) throws IOException {
        byte[] bytes = JSON.writeValueAsBytes(body);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }
}
