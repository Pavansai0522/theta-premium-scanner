package com.premiumscanner.service;

import com.premiumscanner.domain.OptionSnapshot;
import com.premiumscanner.domain.StrangleMetrics;
import com.premiumscanner.support.TestData;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;

import static com.premiumscanner.support.TestData.bd;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class OptionCalculationServiceTest {

    private final OptionCalculationService calc = new OptionCalculationService(TestData.CLOCK);

    @Test
    void midIsAverageOfBidAndAsk() {
        assertThat(calc.midPrice(bd("0.28"), bd("0.32"))).isEqualByComparingTo("0.30");
        assertThat(calc.midPrice(bd("0.35"), bd("0.40"))).isEqualByComparingTo("0.375");
        assertThat(calc.spread(bd("0.35"), bd("0.40"))).isEqualByComparingTo("0.05");
    }

    @Test
    void midIsUndefinedForMissingOrCrossedQuotes() {
        assertThat(calc.midPrice(bd("0.40"), bd("0.35"))).isNull();
        assertThat(calc.midPrice(bd("0.40"), null)).isNull();
        assertThat(calc.midPrice(null, bd("0.40"))).isNull();
        assertThat(calc.midPrice(bd("0.10"), bd("0"))).isNull();
    }

    /** Worked example from the assignment: spot 650, 625 put mid 0.300, 680 call mid 0.375. */
    @Test
    void specExamplePremiumAndBreakevens() {
        BigDecimal total = calc.totalPremium(bd("0.375"), bd("0.300"));
        assertThat(total).isEqualByComparingTo("0.675");
        assertThat(calc.premiumPerContract(total)).isEqualByComparingTo("67.50");
        assertThat(calc.lowerBreakeven(bd("625"), total)).isEqualByComparingTo("624.325");
        assertThat(calc.upperBreakeven(bd("680"), total)).isEqualByComparingTo("680.675");
    }
    /** Worked example from spec section 10: call mid 0.40 + put mid 0.35. */
    @Test
    void specSection10PremiumExample() {
        BigDecimal total = calc.totalPremium(bd("0.40"), bd("0.35"));
        assertThat(total).isEqualByComparingTo("0.75");
        assertThat(calc.premiumPerContract(total)).isEqualByComparingTo("75.00");}

    @Test
    void distanceFromCurrentPrice() {
        assertThat(calc.callDistancePct(bd("680"), bd("650"))).isCloseTo(30.0 / 650, within(1e-7));
        assertThat(calc.putDistancePct(bd("625"), bd("650"))).isCloseTo(25.0 / 650, within(1e-7));
    }

    @Test
    void dteIsCalendarDaysInExchangeTime() {
        assertThat(calc.daysToExpiration(TestData.AUG_21)).isEqualTo(12);
        assertThat(calc.daysToExpiration(LocalDate.of(2026, 8, 9))).isZero();
    }

    @Test
    void contractExpiresAfterTheCloseOnExpirationDay() {
        assertThat(calc.isExpired(LocalDate.of(2026, 8, 8))).isTrue();
        assertThat(calc.isExpired(LocalDate.of(2026, 8, 9))).isFalse(); // 10:00 ET
        OptionCalculationService evening = new OptionCalculationService(
                Clock.fixed(Instant.parse("2026-08-09T20:30:00Z"), TestData.ET)); // 16:30 ET
        assertThat(evening.isExpired(LocalDate.of(2026, 8, 9))).isTrue();
    }

    @Test
    void identifiesOutOfTheMoneyCallsAndPuts() {
        OptionSnapshot otmCall = TestData.call("680", "0.35", "0.40", 0.08, -0.018);
        OptionSnapshot itmCall = TestData.call("640", "11.0", "11.2", 0.70, -0.10);
        OptionSnapshot atmCall = TestData.call("650", "5.0", "5.2", 0.50, -0.20);
        OptionSnapshot otmPut = TestData.put("625", "0.28", "0.32", -0.07, -0.016);
        OptionSnapshot itmPut = TestData.put("660", "11.0", "11.2", -0.70, -0.10);
        assertThat(calc.isOutOfTheMoney(otmCall.contract(), TestData.SPOT)).isTrue();
        assertThat(calc.isOutOfTheMoney(itmCall.contract(), TestData.SPOT)).isFalse();
        assertThat(calc.isOutOfTheMoney(atmCall.contract(), TestData.SPOT)).isFalse();
        assertThat(calc.isOutOfTheMoney(otmPut.contract(), TestData.SPOT)).isTrue();
        assertThat(calc.isOutOfTheMoney(itmPut.contract(), TestData.SPOT)).isFalse();
        assertThat(calc.isInTheMoney(itmCall.contract(), TestData.SPOT)).isTrue();
        assertThat(calc.isInTheMoney(itmPut.contract(), TestData.SPOT)).isTrue();
    }

    @Test
    void expectedMoveUsesSquareRootOfTime() {
        double expected = 650 * 0.20 * Math.sqrt(12 / 365.0);
        assertThat(calc.expectedMove(bd("650"), 0.20, 12)).isCloseTo(expected, within(1e-9));
        assertThat(calc.expectedMove(bd("650"), null, 12)).isNull();
        assertThat(calc.expectedMove(bd("650"), 0.0, 12)).isNull();
    }

    @Test
    void strangleMetricsForSpecExample() {
        OptionSnapshot put = TestData.put("625", "0.28", "0.32", -0.07, -0.016);
        OptionSnapshot call = TestData.call("680", "0.35", "0.40", 0.08, -0.018);

        StrangleMetrics m = calc.strangleMetrics(put, call, TestData.SPOT, 0.19);

        assertThat(m.putMid()).isEqualByComparingTo("0.30");
        assertThat(m.callMid()).isEqualByComparingTo("0.375");
        assertThat(m.totalPremium()).isEqualByComparingTo("0.675");
        assertThat(m.premiumPerContract()).isEqualByComparingTo("67.50");
        assertThat(m.naturalCredit()).isEqualByComparingTo("0.63");
        assertThat(m.lowerBreakeven()).isEqualByComparingTo("624.325");
        assertThat(m.upperBreakeven()).isEqualByComparingTo("680.675");
        assertThat(m.dte()).isEqualTo(12);
        assertThat(m.probabilityOfProfit()).isCloseTo(0.85, within(1e-9));
        assertThat(m.thetaPerDay()).isCloseTo(3.4, within(1e-9));
        assertThat(m.positionDelta()).isCloseTo(-0.01, within(1e-9));
        double em = 650 * 0.19 * Math.sqrt(12 / 365.0);
        assertThat(m.expectedMove()).isCloseTo(em, within(1e-9));
        assertThat(m.putCushion()).isCloseTo((650 - 624.325) / em, within(1e-9));
        assertThat(m.callCushion()).isCloseTo((680.675 - 650) / em, within(1e-9));
    }

    @Test
    void strangleMetricsFallBackToLegIvAverage() {
        OptionSnapshot put = TestData.put("625", "0.28", "0.32", -0.07, -0.016);   // IV 0.20
        OptionSnapshot call = TestData.call("680", "0.35", "0.40", 0.08, -0.018);  // IV 0.18
        StrangleMetrics m = calc.strangleMetrics(put, call, TestData.SPOT, null);
        assertThat(m.impliedVolatility()).isCloseTo(0.19, within(1e-12));
    }
}
