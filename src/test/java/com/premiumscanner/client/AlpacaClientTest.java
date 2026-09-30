package com.premiumscanner.client;

import com.premiumscanner.client.dto.OptionContractDto;
import com.premiumscanner.client.dto.OptionSnapshotDto;
import com.premiumscanner.client.dto.StockSnapshotDto;
import com.premiumscanner.config.AlpacaProperties;
import com.premiumscanner.domain.OptionQuery;
import com.premiumscanner.domain.OptionType;
import com.premiumscanner.exception.AlpacaApiException;
import com.premiumscanner.exception.ConfigurationException;
import com.premiumscanner.exception.PaginationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withTooManyRequests;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withUnauthorizedRequest;

class AlpacaClientTest {

    private static final String SNAPSHOTS = "https://data.test/v1beta1/options/snapshots/SPY";
    private static final String PAGE_ONE = """
            {"snapshots":{
               "SPY260821C00680000":{"latestQuote":{"ap":0.40,"as":12,"ax":"C","bp":0.35,"bs":9,"bx":"X","c":"A","t":"2026-08-07T19:59:59.123456Z"},
                                     "latestTrade":{"c":"I","p":0.37,"s":1,"t":"2026-08-07T19:50:00Z","x":"C"},
                                     "greeks":{"delta":0.08,"gamma":0.004,"rho":0.01,"theta":-0.018,"vega":0.12},
                                     "impliedVolatility":0.18,
                                     "dailyBar":{"c":0.37,"h":0.41,"l":0.33,"n":40,"o":0.34,"t":"2026-08-07T04:00:00Z","v":1234,"vw":0.36}}},
             "next_page_token":"abc+/="}
            """;
    private static final String PAGE_TWO = """
            {"snapshots":{"SPY260821P00625000":{"latestQuote":{"ap":0.32,"as":5,"bp":0.28,"bs":7,"t":"2026-08-07T19:59:59Z"}}},
             "next_page_token":null}
            """;

    private MockRestServiceServer server;
    private AlpacaClient client;
    private final List<Duration> sleeps = new ArrayList<>();

    @BeforeEach
    void setUp() {
        client = newClient("key-id", "secret");
    }

    private AlpacaClient newClient(String key, String secret) {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        AlpacaProperties props = new AlpacaProperties(key, secret, "https://data.test", "https://trading.test",
                "indicative", "iex", 1000, 10000, 5, 2, Duration.ofMillis(100), Duration.ofSeconds(1), null, null, null);
        return new AlpacaClient(builder.build(), props, sleeps::add);
    }

    private static OptionQuery calls() {
        return new OptionQuery(LocalDate.of(2026, 8, 16), LocalDate.of(2026, 9, 23), new BigDecimal("650.0"), null, OptionType.CALL);
    }

    @Test
    void fetchesSnapshotsWithAuthHeadersAndServerSideFilters() {
        server.expect(requestTo(startsWith(SNAPSHOTS)))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("APCA-API-KEY-ID", "key-id"))
                .andExpect(header("APCA-API-SECRET-KEY", "secret"))
                .andExpect(queryParam("feed", "indicative"))
                .andExpect(queryParam("limit", "1000"))
                .andExpect(queryParam("type", "call"))
                .andExpect(queryParam("strike_price_gte", "650.0"))
                .andExpect(queryParam("expiration_date_gte", "2026-08-16"))
                .andExpect(queryParam("expiration_date_lte", "2026-09-23"))
                .andRespond(withSuccess(PAGE_TWO.replace("P00625000", "C00680000"), MediaType.APPLICATION_JSON));

        Map<String, OptionSnapshotDto> result = client.getOptionSnapshots("SPY", calls());

        assertThat(result).containsOnlyKeys("SPY260821C00680000");
        server.verify();
    }

    @Test
    void followsNextPageTokenAndEncodesIt() {
        server.expect(requestTo(startsWith(SNAPSHOTS))).andRespond(withSuccess(PAGE_ONE, MediaType.APPLICATION_JSON));
        server.expect(requestTo(startsWith(SNAPSHOTS)))
                .andExpect(queryParam("page_token", "abc%2B%2F%3D"))
                .andRespond(withSuccess(PAGE_TWO, MediaType.APPLICATION_JSON));

        Map<String, OptionSnapshotDto> result = client.getOptionSnapshots("SPY", calls());

        assertThat(result).containsOnlyKeys("SPY260821C00680000", "SPY260821P00625000");
        OptionSnapshotDto call = result.get("SPY260821C00680000");
        assertThat(call.greeks().delta()).isEqualTo(0.08);
        assertThat(call.greeks().theta()).isEqualTo(-0.018);
        assertThat(call.latestQuote().bidPrice()).isEqualTo(0.35);
        assertThat(call.latestQuote().askPrice()).isEqualTo(0.40);
        assertThat(call.impliedVolatility()).isEqualTo(0.18);
        assertThat(call.dailyBar().volume()).isEqualTo(1234L);
        assertThat(result.get("SPY260821P00625000").greeks()).isNull();
        server.verify();
    }

    @Test
    void emptyResponseIsAnEmptyMap() {
        server.expect(requestTo(startsWith(SNAPSHOTS)))
                .andRespond(withSuccess("{\"snapshots\":{},\"next_page_token\":null}", MediaType.APPLICATION_JSON));
        assertThat(client.getOptionSnapshots("SPY", calls())).isEmpty();
    }

    @Test
    void authenticationFailureIsNotRetried() {
        server.expect(requestTo(startsWith(SNAPSHOTS)))
                .andRespond(withUnauthorizedRequest().body("{\"code\":40110000,\"message\":\"request is not authorized\"}"));

        assertThatThrownBy(() -> client.getOptionSnapshots("SPY", calls()))
                .isInstanceOfSatisfying(AlpacaApiException.class, e -> {
                    assertThat(e.getUpstreamStatus()).isEqualTo(401);
                    assertThat(e.getStatus()).isEqualTo(502);
                    assertThat(e.getCode()).isEqualTo("ALPACA_AUTH_FAILED");
                    assertThat(e.getMessage()).contains("request is not authorized");
                });
        assertThat(sleeps).isEmpty();
        server.verify();
    }

    @Test
    void invalidSymbolIsABadRequest() {
        server.expect(requestTo(startsWith("https://data.test/v1beta1/options/snapshots/ZZZZ")))
                .andRespond(withBadRequest().body("{\"message\":\"invalid underlying symbol\"}"));

        assertThatThrownBy(() -> client.getOptionSnapshots("ZZZZ", null))
                .isInstanceOfSatisfying(AlpacaApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(400);
                    assertThat(e.getCode()).isEqualTo("ALPACA_BAD_REQUEST");
                    assertThat(e.getMessage()).contains("invalid underlying symbol");
                });
    }

    @Test
    void rateLimitIsRetriedHonouringRetryAfter() {
        server.expect(requestTo(startsWith(SNAPSHOTS)))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).header("Retry-After", "2"));
        server.expect(requestTo(startsWith(SNAPSHOTS)))
                .andRespond(withSuccess(PAGE_TWO, MediaType.APPLICATION_JSON));

        assertThat(client.getOptionSnapshots("SPY", calls())).hasSize(1);
        assertThat(sleeps).containsExactly(Duration.ofSeconds(2));
        server.verify();
    }

    @Test
    void rateLimitGivesUpAfterMaxRetriesWithExponentialBackoff() {
        server.expect(ExpectedCount.times(3), requestTo(startsWith(SNAPSHOTS))).andRespond(withTooManyRequests());

        assertThatThrownBy(() -> client.getOptionSnapshots("SPY", calls()))
                .isInstanceOfSatisfying(AlpacaApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(429);
                    assertThat(e.getCode()).isEqualTo("ALPACA_RATE_LIMITED");
                });
        assertThat(sleeps).containsExactly(Duration.ofMillis(100), Duration.ofMillis(200));
        server.verify();
    }

    @Test
    void serverErrorsAreRetriedThenReported() {
        server.expect(ExpectedCount.times(3), requestTo(startsWith(SNAPSHOTS))).andRespond(withServerError());

        assertThatThrownBy(() -> client.getOptionSnapshots("SPY", calls()))
                .isInstanceOfSatisfying(AlpacaApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(502);
                    assertThat(e.getCode()).isEqualTo("ALPACA_UNAVAILABLE");
                });
        assertThat(sleeps).hasSize(2);
    }

    @Test
    void repeatedPageTokenIsDetected() {
        String looping = "{\"snapshots\":{},\"next_page_token\":\"same\"}";
        server.expect(ExpectedCount.times(2), requestTo(startsWith(SNAPSHOTS)))
                .andRespond(withSuccess(looping, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.getOptionSnapshots("SPY", calls()))
                .isInstanceOf(PaginationException.class)
                .hasMessageContaining("same page token");
    }

    @Test
    void runawayPaginationIsCapped() {
        for (int i = 0; i < 5; i++) {
            server.expect(requestTo(startsWith(SNAPSHOTS)))
                    .andRespond(withSuccess("{\"snapshots\":{},\"next_page_token\":\"t" + i + "\"}", MediaType.APPLICATION_JSON));
        }
        assertThatThrownBy(() -> client.getOptionSnapshots("SPY", calls()))
                .isInstanceOf(PaginationException.class)
                .hasMessageContaining("Stopped after 5 pages");
    }

    @Test
    void missingCredentialsFailBeforeAnyRequest() {
        AlpacaClient unconfigured = newClient("", null);
        assertThatThrownBy(() -> unconfigured.getOptionSnapshots("SPY", calls()))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("ALPACA_API_KEY");
        server.verify(); // no requests were made
    }

    @Test
    void readsOpenInterestFromTheTradingApi() {
        server.expect(requestTo(startsWith("https://trading.test/v2/options/contracts")))
                .andExpect(queryParam("underlying_symbols", "SPY"))
                .andExpect(queryParam("status", "active"))
                .andExpect(queryParam("limit", "10000"))
                .andRespond(withSuccess("""
                        {"option_contracts":[{"symbol":"SPY260821C00680000","underlying_symbol":"SPY","type":"call",
                          "strike_price":"680","expiration_date":"2026-08-21","open_interest":"2500",
                          "open_interest_date":"2026-08-06","status":"active","tradable":true}],
                         "next_page_token":null}
                        """, MediaType.APPLICATION_JSON));

        List<OptionContractDto> contracts = client.getOptionContracts("SPY", calls());

        assertThat(contracts).singleElement().satisfies(c -> {
            assertThat(c.openInterest()).isEqualTo("2500");
            assertThat(c.strikePrice()).isEqualTo("680");
        });
    }

    @Test
    void readsStockSnapshot() {
        server.expect(requestTo("https://data.test/v2/stocks/SPY/snapshot?feed=iex"))
                .andRespond(withSuccess("""
                        {"symbol":"SPY","latestTrade":{"p":650.01,"s":100,"t":"2026-08-07T19:59:59Z"},
                         "latestQuote":{"bp":650.0,"ap":650.02,"bs":1,"as":2,"t":"2026-08-07T19:59:58Z"},
                         "dailyBar":{"c":650.01,"v":1000},"prevDailyBar":{"c":645.2}}
                        """, MediaType.APPLICATION_JSON));

        StockSnapshotDto s = client.getStockSnapshot("SPY");

        assertThat(s.latestTrade().price()).isEqualTo(650.01);
        assertThat(s.prevDailyBar().close()).isEqualTo(645.2);
    }
}
