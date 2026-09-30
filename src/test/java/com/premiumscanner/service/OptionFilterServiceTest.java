package com.premiumscanner.service;

import com.premiumscanner.domain.LegFilterResult;
import com.premiumscanner.domain.OptionSnapshot;
import com.premiumscanner.domain.OptionType;
import com.premiumscanner.domain.RejectionReason;
import com.premiumscanner.domain.StrategyParameters;
import com.premiumscanner.support.TestData;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static com.premiumscanner.support.TestData.bd;
import static org.assertj.core.api.Assertions.assertThat;

class OptionFilterServiceTest {

    private final OptionFilterService filter = new OptionFilterService(new OptionCalculationService(TestData.CLOCK));
    private final StrategyParameters params = TestData.params();

    private Optional<RejectionReason> check(OptionSnapshot o) {
        return filter.rejectionReason(o, TestData.SPOT, params);
    }

    @Test
    void separatesEligibleCallsAndPuts() {
        OptionSnapshot call = TestData.call("680", "0.35", "0.40", 0.08, -0.018);
        OptionSnapshot put = TestData.put("625", "0.28", "0.32", -0.07, -0.016);
        LegFilterResult result = filter.filter(List.of(call, put), TestData.SPOT, params);
        assertThat(result.eligibleCalls()).containsExactly(call);
        assertThat(result.eligiblePuts()).containsExactly(put);
        assertThat(result.callsEvaluated()).isEqualTo(1);
        assertThat(result.putsEvaluated()).isEqualTo(1);
    }

    @Test
    void rejectsInTheMoneyAndAtTheMoney() {
        assertThat(check(TestData.call("640", "11.0", "11.2", 0.12, -0.02))).contains(RejectionReason.NOT_OUT_OF_THE_MONEY);
        assertThat(check(TestData.put("660", "11.0", "11.2", -0.12, -0.02))).contains(RejectionReason.NOT_OUT_OF_THE_MONEY);
        assertThat(check(TestData.call("650", "5.0", "5.2", 0.12, -0.02))).contains(RejectionReason.NOT_OUT_OF_THE_MONEY);
    }

    @Test
    void deltaFilterUsesAbsoluteValueOnBothSides() {
        assertThat(check(TestData.call("660", "3.1", "3.2", 0.30, -0.2))).contains(RejectionReason.DELTA_OUT_OF_RANGE);
        assertThat(check(TestData.call("700", "0.25", "0.30", 0.04, -0.02))).contains(RejectionReason.DELTA_OUT_OF_RANGE);
        assertThat(check(TestData.put("600", "0.25", "0.30", -0.03, -0.02))).contains(RejectionReason.DELTA_OUT_OF_RANGE);
        assertThat(check(TestData.put("625", "0.28", "0.32", -0.15, -0.02))).isEmpty(); // inclusive bound
        assertThat(check(TestData.call("690", "0.28", "0.32", 0.05, -0.02))).isEmpty();  // inclusive bound
    }

    @Test
    void thetaFilterComparesMagnitude() {
        assertThat(check(TestData.put("620", "0.22", "0.26", -0.05, -0.014))).contains(RejectionReason.THETA_BELOW_MIN);
        assertThat(check(TestData.put("620", "0.22", "0.26", -0.05, -0.015))).isEmpty();
    }

    @Test
    void dteFilter() {
        OptionSnapshot tooSoon = TestData.leg(OptionType.CALL, LocalDate.of(2026, 8, 14), "680", "0.35", "0.40", 0.08, -0.03, 1000L, 10, 0.2);
        OptionSnapshot tooFar = TestData.leg(OptionType.CALL, LocalDate.of(2026, 10, 16), "700", "0.35", "0.40", 0.08, -0.03, 1000L, 10, 0.2);
        assertThat(check(tooSoon)).contains(RejectionReason.DTE_OUT_OF_RANGE);
        assertThat(check(tooFar)).contains(RejectionReason.DTE_OUT_OF_RANGE);
    }

    @Test
    void zeroDteAllowedOnlyWhenRequested() {
        OptionSnapshot today = TestData.leg(OptionType.CALL, LocalDate.of(2026, 8, 9), "655", "0.35", "0.40", 0.08, -0.30, 1000L, 10, 0.2);
        assertThat(check(today)).contains(RejectionReason.DTE_OUT_OF_RANGE);
        StrategyParameters zeroDte = new StrategyParameters(0.05, 0.15, 0.015, 0, 45, bd("0.20"), bd("0.20"), 500, 0, bd("0"), 25);
        assertThat(filter.rejectionReason(today, TestData.SPOT, zeroDte)).isEmpty();
    }

    @Test
    void quoteQualityChecks() {
        assertThat(check(TestData.put("610", "0", "0.05", -0.06, -0.02))).contains(RejectionReason.ZERO_BID);
        assertThat(check(TestData.put("610", "0.10", null, -0.06, -0.02))).contains(RejectionReason.MISSING_QUOTE);
        assertThat(check(TestData.put("610", "0.50", "0.40", -0.06, -0.02))).contains(RejectionReason.INVALID_QUOTE);
        assertThat(check(TestData.put("630", "0.45", "0.95", -0.10, -0.02))).contains(RejectionReason.SPREAD_TOO_WIDE);
        assertThat(check(TestData.put("615", "0.10", "0.14", -0.06, -0.02))).contains(RejectionReason.PREMIUM_TOO_LOW);
    }

    @Test
    void missingOrInconsistentGreeks() {
        OptionSnapshot noGreeks = TestData.leg(OptionType.CALL, TestData.AUG_21, "700", "0.30", "0.35", null, null, 1000L, 10, null);
        assertThat(check(noGreeks)).contains(RejectionReason.MISSING_GREEKS);
        assertThat(check(TestData.call("680", "0.35", "0.40", -0.08, -0.02))).contains(RejectionReason.INVALID_GREEKS);
        assertThat(check(TestData.put("625", "0.28", "0.32", 0.07, -0.02))).contains(RejectionReason.INVALID_GREEKS);
        assertThat(check(TestData.put("625", "0.28", "0.32", -0.07, 0.02))).contains(RejectionReason.INVALID_GREEKS);
    }

    @Test
    void liquidityChecks() {
        OptionSnapshot lowOi = TestData.leg(OptionType.PUT, TestData.AUG_21, "625", "0.28", "0.32", -0.07, -0.02, 100L, 10, 0.2);
        OptionSnapshot noOi = TestData.leg(OptionType.PUT, TestData.AUG_21, "625", "0.28", "0.32", -0.07, -0.02, null, 10, 0.2);
        assertThat(check(lowOi)).contains(RejectionReason.OPEN_INTEREST_TOO_LOW);
        assertThat(check(noOi)).contains(RejectionReason.OPEN_INTEREST_UNAVAILABLE);

        StrategyParameters needsVolume = new StrategyParameters(0.05, 0.15, 0.015, 7, 45, bd("0.20"), bd("0.20"), 500, 50, bd("0"), 25);
        OptionSnapshot quiet = TestData.leg(OptionType.PUT, TestData.AUG_21, "625", "0.28", "0.32", -0.07, -0.02, 1000L, 10, 0.2);
        assertThat(filter.rejectionReason(quiet, TestData.SPOT, needsVolume)).contains(RejectionReason.VOLUME_TOO_LOW);
    }

    @Test
    void countsRejectionsPerSide() {
        LegFilterResult result = filter.filter(List.of(
                TestData.call("660", "3.1", "3.2", 0.30, -0.2),
                TestData.call("700", "0.25", "0.30", 0.04, -0.02),
                TestData.put("620", "0.22", "0.26", -0.05, -0.014)), TestData.SPOT, params);
        assertThat(result.callRejections()).containsEntry(RejectionReason.DELTA_OUT_OF_RANGE, 2);
        assertThat(result.putRejections()).containsEntry(RejectionReason.THETA_BELOW_MIN, 1);
        assertThat(result.eligibleCalls()).isEmpty();
    }
}
