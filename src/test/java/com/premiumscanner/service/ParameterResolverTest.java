package com.premiumscanner.service;

import com.premiumscanner.config.ScannerProperties;
import com.premiumscanner.domain.StrategyParameterRequest;
import com.premiumscanner.domain.StrategyParameters;
import com.premiumscanner.exception.InvalidParameterException;
import com.premiumscanner.support.TestData;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ParameterResolverTest {

    private final ParameterResolver resolver = new ParameterResolver(TestData.properties());

    @Test
    void usesConfiguredDefaults() {
        StrategyParameters p = resolver.defaults();
        assertThat(p.minDelta()).isEqualTo(0.05);
        assertThat(p.maxDelta()).isEqualTo(0.15);
        assertThat(p.minTheta()).isEqualTo(0.015);
        assertThat(p.minDte()).isEqualTo(7);
        assertThat(p.maxDte()).isEqualTo(45);
        assertThat(p.minOpenInterest()).isEqualTo(500);
    }

    @Test
    void requestValuesOverrideDefaults() {
        StrategyParameterRequest r = new StrategyParameterRequest(0.10, 0.20, 0.02, 14, 60, null, null, 1000L, null, null, 10);
        StrategyParameters p = resolver.resolve(r);
        assertThat(p.minDelta()).isEqualTo(0.10);
        assertThat(p.maxDte()).isEqualTo(60);
        assertThat(p.minOpenInterest()).isEqualTo(1000);
        assertThat(p.minPremium()).isEqualByComparingTo("0.20");
        assertThat(p.topN()).isEqualTo(10);
    }

    @Test
    void rejectsInconsistentValuesWithEveryProblemListed() {
        StrategyParameterRequest r = new StrategyParameterRequest(0.20, 0.10, -1.0, 30, 7, null, null, -5L, null, null, null);
        assertThatThrownBy(() -> resolver.resolve(r))
                .isInstanceOf(InvalidParameterException.class)
                .satisfies(e -> {
                    InvalidParameterException ex = (InvalidParameterException) e;
                    assertThat(ex.getStatus()).isEqualTo(400);
                    assertThat(ex.getDetails()).hasSizeGreaterThanOrEqualTo(4);
                });
    }

    @Test
    void rejectsDeltaThatWouldAllowAtTheMoneyLegs() {
        StrategyParameterRequest r = new StrategyParameterRequest(0.10, 0.55, null, null, null, null, null, null, null, null, null);
        assertThatThrownBy(() -> resolver.resolve(r)).hasMessageContaining("maxDelta must be below 0.50");
    }

    @Test
    void failsClearlyWhenNoDefaultIsConfigured() {
        ParameterResolver unconfigured = new ParameterResolver(new ScannerProperties(null, null, null, null, null));
        assertThatThrownBy(unconfigured::defaults)
                .isInstanceOf(InvalidParameterException.class)
                .hasMessageContaining("minDelta is required");
    }
}
