package com.premiumscanner.service;

import com.premiumscanner.domain.OptionSnapshot;
import com.premiumscanner.domain.OptionType;
import com.premiumscanner.domain.StrangleCandidate;
import com.premiumscanner.service.StrangleRankingService.StranglePair;
import com.premiumscanner.support.TestData;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class StrangleRankingServiceTest {

    private final OptionCalculationService calc = new OptionCalculationService(TestData.CLOCK);
    private final StrangleRankingService ranking = new StrangleRankingService(TestData.properties());

    private StranglePair pair(OptionSnapshot put, OptionSnapshot call, Double atmIv) {
        return new StranglePair(put, call, calc.strangleMetrics(put, call, TestData.SPOT, atmIv));
    }

    @Test
    void doesNotSimplyPickTheLowestDelta() {
        // Farthest OTM, but thin premium, wide relative spread and almost no open interest.
        OptionSnapshot farPut = TestData.leg(OptionType.PUT, TestData.AUG_21, "600", "0.20", "0.30", -0.05, -0.015, 520L, 3, 0.24);
        OptionSnapshot farCall = TestData.leg(OptionType.CALL, TestData.AUG_21, "700", "0.20", "0.30", 0.05, -0.015, 510L, 2, 0.17);
        // Closer, but well paid and liquid, breakevens still outside one expected move.
        OptionSnapshot put = TestData.leg(OptionType.PUT, TestData.AUG_21, "620", "0.55", "0.58", -0.10, -0.030, 25_000L, 9_000, 0.21);
        OptionSnapshot call = TestData.leg(OptionType.CALL, TestData.AUG_21, "675", "0.60", "0.63", 0.11, -0.032, 22_000L, 8_000, 0.18);

        List<StrangleCandidate> ranked = ranking.rank(List.of(pair(farPut, farCall, 0.19), pair(put, call, 0.19)), TestData.params());

        assertThat(ranked.get(0).shortPut().strike()).isEqualByComparingTo("620");
        assertThat(ranked.get(0).shortCall().strike()).isEqualByComparingTo("675");
        assertThat(ranked.get(1).metrics().probabilityOfProfit()).isGreaterThan(ranked.get(0).metrics().probabilityOfProfit());
    }

    @Test
    void penalisesBreakevensInsideTheExpectedMove() {
        OptionSnapshot nearPut = TestData.leg(OptionType.PUT, TestData.AUG_21, "640", "0.90", "0.95", -0.15, -0.05, 20_000L, 5_000, 0.20);
        OptionSnapshot nearCall = TestData.leg(OptionType.CALL, TestData.AUG_21, "660", "0.90", "0.95", 0.15, -0.05, 20_000L, 5_000, 0.18);
        StranglePair near = pair(nearPut, nearCall, 0.19);
        assertThat(near.metrics().putCushion()).isLessThan(1.0);

        StrangleCandidate c = ranking.rank(List.of(near), TestData.params()).get(0);
        assertThat(c.scoreBreakdown().cushion()).isLessThan(1.0);
        assertThat(c.flags()).anyMatch(f -> f.contains("inside 1 expected move"));
    }

    @Test
    void balancedDeltaBeatsLopsidedWhenOtherwiseEqual() {
        OptionSnapshot put = TestData.put("625", "0.28", "0.32", -0.08, -0.02);
        OptionSnapshot balancedCall = TestData.call("680", "0.28", "0.32", 0.08, -0.02);
        OptionSnapshot skewedPut = TestData.put("630", "0.28", "0.32", -0.14, -0.02);
        OptionSnapshot skewedCall = TestData.call("685", "0.28", "0.32", 0.02, -0.02);
        StranglePair balanced = pair(put, balancedCall, 0.19);
        StranglePair skewed = pair(skewedPut, skewedCall, 0.19);

        assertThat(ranking.deltaBalance(put, balancedCall)).isCloseTo(1.0, within(1e-12));
        assertThat(ranking.deltaBalance(skewedPut, skewedCall)).isLessThan(0.5);
        List<StrangleCandidate> ranked = ranking.rank(List.of(skewed, balanced), TestData.params());
        assertThat(ranked.get(0).id()).isEqualTo(put.symbol() + "_" + balancedCall.symbol());
    }

    @Test
    void scoresAreBoundedAndRanksAreConsecutive() {
        List<StranglePair> pairs = List.of(
                pair(TestData.put("625", "0.28", "0.32", -0.07, -0.016), TestData.call("680", "0.35", "0.40", 0.08, -0.018), 0.19),
                pair(TestData.put("620", "0.22", "0.26", -0.05, -0.015), TestData.call("685", "0.27", "0.31", 0.06, -0.015), 0.19),
                pair(TestData.put("630", "0.40", "0.44", -0.10, -0.022), TestData.call("675", "0.45", "0.49", 0.11, -0.024), 0.19));

        List<StrangleCandidate> ranked = ranking.rank(pairs, TestData.params());

        assertThat(ranked).extracting(StrangleCandidate::rank).containsExactly(1, 2, 3);
        assertThat(ranked).allSatisfy(c -> assertThat(c.score()).isBetween(0.0, 100.0));
        assertThat(ranked.get(0).score()).isGreaterThanOrEqualTo(ranked.get(1).score());
        assertThat(ranked.get(1).score()).isGreaterThanOrEqualTo(ranked.get(2).score());
    }

    @Test
    void missingIvIsNeutralAndFlagged() {
        OptionSnapshot put = TestData.leg(OptionType.PUT, TestData.AUG_21, "625", "0.28", "0.32", -0.07, -0.016, 2000L, 100, null);
        OptionSnapshot call = TestData.leg(OptionType.CALL, TestData.AUG_21, "680", "0.35", "0.40", 0.08, -0.018, 2000L, 100, null);
        StrangleCandidate c = ranking.rank(List.of(pair(put, call, null)), TestData.params()).get(0);
        assertThat(c.metrics().expectedMove()).isNull();
        assertThat(c.scoreBreakdown().cushion()).isEqualTo(0.5);
        assertThat(c.flags()).anyMatch(f -> f.startsWith("IV unavailable"));
    }

    @Test
    void liquidityIsDrivenByTheWeakerLeg() {
        OptionSnapshot liquid = TestData.leg(OptionType.PUT, TestData.AUG_21, "625", "0.30", "0.31", -0.07, -0.016, 10_000L, 5_000, 0.2);
        OptionSnapshot thin = TestData.leg(OptionType.CALL, TestData.AUG_21, "680", "0.30", "0.50", 0.08, -0.018, 600L, 0, 0.2);
        assertThat(ranking.legLiquidity(liquid)).isGreaterThan(0.9);
        assertThat(ranking.pairLiquidity(liquid, thin)).isEqualTo(ranking.legLiquidity(thin));
    }

    @Test
    void dteFitPeaksMidWindow() {
        assertThat(ranking.dteFit(26, TestData.params())).isEqualTo(1.0);
        assertThat(ranking.dteFit(7, TestData.params())).isCloseTo(0.5, within(1e-12));
        assertThat(ranking.dteFit(45, TestData.params())).isCloseTo(0.5, within(1e-12));
    }
}
