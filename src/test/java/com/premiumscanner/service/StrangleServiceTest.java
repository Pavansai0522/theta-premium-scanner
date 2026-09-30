package com.premiumscanner.service;

import com.premiumscanner.domain.OptionSnapshot;
import com.premiumscanner.domain.OptionType;
import com.premiumscanner.domain.StrangleCandidate;
import com.premiumscanner.domain.StrangleScanResult;
import com.premiumscanner.domain.StrategyParameters;
import com.premiumscanner.support.TestData;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static com.premiumscanner.support.TestData.bd;
import static org.assertj.core.api.Assertions.assertThat;

/** Pipeline tests over in-memory data: filter -> pair by expiration -> rank -> top N. */
class StrangleServiceTest {

    private final OptionCalculationService calc = new OptionCalculationService(TestData.CLOCK);
    private final StrangleService service = new StrangleService(null, new OptionFilterService(calc), calc,
            new StrangleRankingService(TestData.properties()), new ParameterResolver(TestData.properties()), TestData.properties());

    private StrangleScanResult scan(List<OptionSnapshot> options, StrategyParameters p) {
        return service.scan("SPY", TestData.spy(), options, p, List.of());
    }

    @Test
    void producesTheSpecExampleCandidate() {
        OptionSnapshot put = TestData.put("625", "0.28", "0.32", -0.07, -0.016);
        OptionSnapshot call = TestData.call("680", "0.35", "0.40", 0.08, -0.018);

        StrangleScanResult result = scan(List.of(put, call), TestData.params());

        assertThat(result.candidates()).hasSize(1);
        StrangleCandidate c = result.candidates().get(0);
        assertThat(c.rank()).isEqualTo(1);
        assertThat(c.metrics().totalPremium()).isEqualByComparingTo("0.675");
        assertThat(c.metrics().lowerBreakeven()).isEqualByComparingTo("624.325");
        assertThat(c.metrics().upperBreakeven()).isEqualByComparingTo("680.675");
        assertThat(c.metrics().premiumPerContract()).isEqualByComparingTo("67.50");
    }

    @Test
    void pairsOnlyWithinTheSameExpiration() {
        OptionSnapshot call21 = TestData.call("680", "0.35", "0.40", 0.08, -0.018);
        OptionSnapshot put28 = TestData.leg(OptionType.PUT, TestData.AUG_28, "615", "0.38", "0.42", -0.09, -0.021, 1800L, 100, 0.2);

        StrangleScanResult result = scan(List.of(call21, put28), TestData.params());

        assertThat(result.eligibleCalls()).isEqualTo(1);
        assertThat(result.eligiblePuts()).isEqualTo(1);
        assertThat(result.pairsEvaluated()).isZero();
        assertThat(result.candidates()).isEmpty();
        assertThat(result.warnings()).anyMatch(w -> w.startsWith("No call/put pair"));
    }

    @Test
    void appliesMinimumTotalPremiumAndTopN() {
        List<OptionSnapshot> legs = List.of(
                TestData.put("625", "0.28", "0.32", -0.07, -0.016),
                TestData.put("620", "0.22", "0.26", -0.05, -0.015),
                TestData.call("680", "0.35", "0.40", 0.08, -0.018),
                TestData.call("685", "0.27", "0.31", 0.06, -0.015));
        assertThat(scan(legs, TestData.params()).candidates()).hasSize(4);

        StrategyParameters minCredit = new StrategyParameters(0.05, 0.15, 0.015, 7, 45, bd("0.20"), bd("0.20"), 500, 0, bd("0.60"), 25);
        assertThat(scan(legs, minCredit).candidates())
                .allSatisfy(c -> assertThat(c.metrics().totalPremium()).isGreaterThanOrEqualTo(bd("0.60")));

        StrategyParameters top2 = new StrategyParameters(0.05, 0.15, 0.015, 7, 45, bd("0.20"), bd("0.20"), 500, 0, bd("0"), 2);
        StrangleScanResult limited = scan(legs, top2);
        assertThat(limited.candidates()).hasSize(2);
        assertThat(limited.pairsEvaluated()).isEqualTo(4);
    }

    @Test
    void neverRecommendsInTheMoneyLegs() {
        List<OptionSnapshot> legs = List.of(
                TestData.call("640", "11.0", "11.2", 0.10, -0.02),  // ITM with an (implausible) OTM-like delta
                TestData.put("660", "11.0", "11.2", -0.10, -0.02),
                TestData.put("625", "0.28", "0.32", -0.07, -0.016),
                TestData.call("680", "0.35", "0.40", 0.08, -0.018));
        StrangleScanResult result = scan(legs, TestData.params());
        assertThat(result.candidates()).hasSize(1);
        assertThat(result.callRejections()).containsKey(com.premiumscanner.domain.RejectionReason.NOT_OUT_OF_THE_MONEY);
    }

    @Test
    void atTheMoneyIvComesFromNearestStrikes() {
        List<OptionSnapshot> legs = List.of(
                TestData.leg(OptionType.CALL, TestData.AUG_21, "651", "5", "5.2", 0.48, -0.2, 1000L, 1, 0.16),
                TestData.leg(OptionType.CALL, TestData.AUG_21, "680", "0.35", "0.40", 0.08, -0.02, 1000L, 1, 0.14),
                TestData.leg(OptionType.PUT, TestData.AUG_21, "649", "5", "5.2", -0.48, -0.2, 1000L, 1, 0.20),
                TestData.leg(OptionType.PUT, TestData.AUG_21, "625", "0.3", "0.32", -0.07, -0.02, 1000L, 1, 0.26));
        Map<LocalDate, Double> iv = StrangleService.atTheMoneyIv(legs, TestData.SPOT);
        assertThat(iv.get(TestData.AUG_21)).isCloseTo(0.18, org.assertj.core.api.Assertions.within(1e-12));
    }
}
