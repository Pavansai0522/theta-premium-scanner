package com.premiumscanner.web;

import com.premiumscanner.domain.OptionChain;
import com.premiumscanner.domain.StrategyParameterRequest;
import com.premiumscanner.exception.AlpacaApiException;
import com.premiumscanner.exception.InvalidParameterException;
import com.premiumscanner.exception.InvalidSymbolException;
import com.premiumscanner.exception.NoOptionsAvailableException;
import com.premiumscanner.service.OptionChainService;
import com.premiumscanner.service.ParameterResolver;
import com.premiumscanner.service.StrangleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Controller + error mapping, without a Spring context (fast and deterministic). */
class OptionsApiControllerTest {

    private final OptionChainService chainService = mock(OptionChainService.class);
    private final StrangleService strangleService = mock(StrangleService.class);
    private final ParameterResolver resolver = mock(ParameterResolver.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new OptionsApiController(chainService, strangleService, resolver))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void chainPassesQueryParameters() throws Exception {
        when(chainService.chain("SPY", 0, 30, 0.1)).thenReturn(new OptionChain("SPY", new BigDecimal("650"),
                Instant.parse("2026-08-07T19:59:59Z"), "latest trade", "indicative", Instant.now(), 0, 0, List.of(), List.of()));

        mvc.perform(get("/api/options/chain/SPY").param("minDte", "0").param("maxDte", "30").param("strikeWindowPct", "0.1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.symbol").value("SPY"))
                .andExpect(jsonPath("$.underlyingPrice").value(650));
    }

    @Test
    void strangleBindsStrategyParameters() throws Exception {
        mvc.perform(get("/api/options/strangle/SPY")
                        .param("minDelta", "0.05").param("maxDelta", "0.15").param("minTheta", "0.015")
                        .param("minDte", "7").param("maxDte", "45").param("minPremium", "0.20")
                        .param("maxSpread", "0.20").param("minOpenInterest", "500"))
                .andExpect(status().isOk());

        ArgumentCaptor<StrategyParameterRequest> captor = ArgumentCaptor.forClass(StrategyParameterRequest.class);
        verify(strangleService).scan(eq("SPY"), captor.capture());
        StrategyParameterRequest r = captor.getValue();
        assertThat(r.minDelta()).isEqualTo(0.05);
        assertThat(r.maxDte()).isEqualTo(45);
        assertThat(r.minPremium()).isEqualByComparingTo("0.20");
        assertThat(r.minOpenInterest()).isEqualTo(500L);
        assertThat(r.minVolume()).isNull();
    }

    @Test
    void nonNumericParameterIs400() throws Exception {
        mvc.perform(get("/api/options/strangle/SPY").param("minDelta", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETERS"));
    }

    @Test
    void invalidParametersAre400WithDetails() throws Exception {
        when(strangleService.scan(anyString(), any()))
                .thenThrow(new InvalidParameterException(List.of("minDelta (0.2) must not exceed maxDelta (0.1)")));

        mvc.perform(get("/api/options/strangle/SPY").param("minDelta", "0.2").param("maxDelta", "0.1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETERS"))
                .andExpect(jsonPath("$.details[0]").value("minDelta (0.2) must not exceed maxDelta (0.1)"));
    }

    @Test
    void unknownSymbolIs404() throws Exception {
        when(strangleService.scan(eq("ZZZZ"), any())).thenThrow(new InvalidSymbolException("ZZZZ", "no data"));
        mvc.perform(get("/api/options/strangle/ZZZZ"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INVALID_SYMBOL"));
    }

    @Test
    void noOptionsIs404() throws Exception {
        when(chainService.chain(eq("BRK.A"), any(), any(), any())).thenThrow(new NoOptionsAvailableException("BRK.A", "between 0 and 45 DTE"));
        mvc.perform(get("/api/options/chain/BRK.A"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NO_OPTIONS_AVAILABLE"));
    }

    @Test
    void upstreamAuthFailureIs502() throws Exception {
        when(strangleService.scan(anyString(), any()))
                .thenThrow(AlpacaApiException.fromStatus(401, "option snapshots for SPY", "unauthorized", null));
        mvc.perform(get("/api/options/strangle/SPY"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("ALPACA_AUTH_FAILED"));
    }

    @Test
    void upstreamForbiddenIs502() throws Exception {
        when(strangleService.scan(anyString(), any()))
                .thenThrow(AlpacaApiException.fromStatus(403, "option snapshots for SPY", "forbidden", null));
        mvc.perform(get("/api/options/strangle/SPY"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("ALPACA_FORBIDDEN"));
    }

    @Test
    void rateLimitIs429WithRetryAfter() throws Exception {
        when(strangleService.scan(anyString(), any()))
                .thenThrow(AlpacaApiException.fromStatus(429, "option snapshots for SPY", "too many requests", Duration.ofSeconds(3)));
        mvc.perform(get("/api/options/strangle/SPY"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "3"))
                .andExpect(jsonPath("$.retryAfterSeconds").value(3));
    }

    @Test
    void unexpectedErrorIs500WithoutLeakingDetails() throws Exception {
        when(strangleService.scan(anyString(), any())).thenThrow(new IllegalStateException("boom: secret internals"));
        mvc.perform(get("/api/options/strangle/SPY"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("Unexpected error; see server logs"));
    }
}
