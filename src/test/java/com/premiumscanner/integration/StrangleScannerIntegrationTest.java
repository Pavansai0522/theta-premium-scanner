package com.premiumscanner.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.premiumscanner.support.TestData;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end: HTTP request -> controller -> services -> real AlpacaClient over HTTP (with pagination) ->
 * fake Alpaca -> JSON / HTML response. Only the upstream is simulated.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StrangleScannerIntegrationTest {

    static final FakeAlpacaServer ALPACA = FakeAlpacaServer.start();
    private static final String SPEC_PARAMS = "minDelta=0.05&maxDelta=0.15&minTheta=0.015&minDte=7&maxDte=45"
            + "&minPremium=0.20&maxSpread=0.20&minOpenInterest=500";

    @DynamicPropertySource
    static void alpaca(DynamicPropertyRegistry registry) {
        registry.add("alpaca.data-base-url", ALPACA::baseUrl);
        registry.add("alpaca.trading-base-url", ALPACA::baseUrl);
        registry.add("alpaca.api-key", () -> "test-key");
        registry.add("alpaca.api-secret", () -> "test-secret");
        registry.add("alpaca.cache-ttl", () -> "0s");
    }

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock fixedTestClock() {
            return TestData.CLOCK;
        }
    }

    @AfterAll
    static void stop() {
        ALPACA.stop();
    }

    @Autowired
    TestRestTemplate http;

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void scansFiltersPairsAndRanksShortStrangles() throws Exception {
        ResponseEntity<String> response = http.getForEntity("/api/options/strangle/SPY?" + SPEC_PARAMS, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = json.readTree(response.getBody());
        assertThat(body.get("underlyingPrice").decimalValue()).isEqualByComparingTo("650.0");
        assertThat(body.get("eligibleCalls").asInt()).isEqualTo(3);
        assertThat(body.get("eligiblePuts").asInt()).isEqualTo(2);
        assertThat(body.get("callRejections").get("DELTA_OUT_OF_RANGE").asInt()).isEqualTo(2);
        assertThat(body.get("callRejections").get("MISSING_GREEKS").asInt()).isEqualTo(1);
        assertThat(body.get("putRejections").get("ZERO_BID").asInt()).isEqualTo(1);
        assertThat(body.get("putRejections").get("THETA_BELOW_MIN").asInt()).isEqualTo(1);
        assertThat(body.get("putRejections").get("SPREAD_TOO_WIDE").asInt()).isEqualTo(1);

        JsonNode candidates = body.get("candidates");
        assertThat(candidates).hasSize(3);
        List<String> pairs = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            JsonNode c = candidates.get(i);
            assertThat(c.get("rank").asInt()).isEqualTo(i + 1);
            assertThat(c.get("score").asDouble()).isBetween(0.0, 100.0);
            pairs.add(c.get("shortPut").get("contract").get("strike").asText() + "/" + c.get("shortCall").get("contract").get("strike").asText());
            // Every recommended leg is out of the money.
            assertThat(new BigDecimal(c.get("shortCall").get("contract").get("strike").asText())).isGreaterThan(new BigDecimal("650"));
            assertThat(new BigDecimal(c.get("shortPut").get("contract").get("strike").asText())).isLessThan(new BigDecimal("650"));
        }
        assertThat(pairs).containsExactlyInAnyOrder("625/680", "625/685", "615/690");

        JsonNode spec = findPair(candidates, "625", "680");
        JsonNode m = spec.get("metrics");
        assertThat(m.get("totalPremium").decimalValue()).isEqualByComparingTo("0.675");
        assertThat(m.get("premiumPerContract").decimalValue()).isEqualByComparingTo("67.50");
        assertThat(m.get("lowerBreakeven").decimalValue()).isEqualByComparingTo("624.325");
        assertThat(m.get("upperBreakeven").decimalValue()).isEqualByComparingTo("680.675");
        assertThat(m.get("dte").asInt()).isEqualTo(12);
        assertThat(m.get("expectedMove").asDouble()).isPositive();
        assertThat(spec.get("shortCall").get("openInterest").asLong()).isEqualTo(2500);
    }

    @Test
    void chainGroupsExpirationsAndKeepsEveryContract() throws Exception {
        ResponseEntity<String> response = http.getForEntity("/api/options/chain/SPY?maxDte=30", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = json.readTree(response.getBody());
        assertThat(body.get("totalContracts").asInt()).isEqualTo(FakeAlpacaServer.OPTIONS.size());
        assertThat(body.get("contractsMissingGreeks").asInt()).isEqualTo(1);
        JsonNode expirations = body.get("expirations");
        assertThat(expirations).hasSize(2);
        assertThat(expirations.get(0).get("expiration").asText()).isEqualTo("2026-08-21");
        assertThat(expirations.get(0).get("dte").asInt()).isEqualTo(12);
        assertThat(expirations.get(0).get("monthly").asBoolean()).isTrue();
        assertThat(expirations.get(1).get("monthly").asBoolean()).isFalse();
        assertThat(ALPACA.snapshotRequests.get()).isGreaterThan(1); // pagination was exercised
    }

    @Test
    void invalidAndUnknownSymbols() throws Exception {
        ResponseEntity<String> bad = http.getForEntity("/api/options/strangle/sp$y", String.class);
        assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        ResponseEntity<String> unknown = http.getForEntity("/api/options/strangle/ZZZZ", String.class);
        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(json.readTree(unknown.getBody()).get("code").asText()).isEqualTo("INVALID_SYMBOL");
    }

    @Test
    void invalidParametersAreRejected() throws Exception {
        ResponseEntity<String> response = http.getForEntity("/api/options/strangle/SPY?minDelta=0.3&maxDelta=0.1", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(json.readTree(response.getBody()).get("code").asText()).isEqualTo("INVALID_PARAMETERS");
    }

    @Test
    void uiRendersChainAndScanner() {
        ResponseEntity<String> page = http.getForEntity("/scan?symbol=spy&" + SPEC_PARAMS, String.class);

        assertThat(page.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(page.getBody())
                .contains("Option chain")
                .contains("21 Aug 26")
                .contains("28 Aug 26 (W)")
                .contains("Recommended Short Strangle Candidates")
                .contains("680.675")
                .contains("624.325")
                .contains("$67.50");
    }

    @Test
    void uiShowsAFriendlyErrorForUnknownSymbols() {
        ResponseEntity<String> page = http.getForEntity("/scan?symbol=ZZZZ", String.class);
        assertThat(page.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(page.getBody()).contains("Unknown or invalid symbol");
    }

    private static JsonNode findPair(JsonNode candidates, String put, String call) {
        for (JsonNode c : candidates) {
            if (c.get("shortPut").get("contract").get("strike").asText().equals(put)
                    && c.get("shortCall").get("contract").get("strike").asText().equals(call)) {
                return c;
            }
        }
        throw new AssertionError("pair " + put + "/" + call + " not found");
    }
}
