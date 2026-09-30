package com.premiumscanner.service;

import com.premiumscanner.domain.ExpirationGroup;
import com.premiumscanner.domain.OptionChain;
import com.premiumscanner.domain.OptionDataset;
import com.premiumscanner.domain.OptionType;
import com.premiumscanner.domain.StrikeRow;
import com.premiumscanner.support.TestData;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OptionChainServiceTest {

    private final OptionCalculationService calc = new OptionCalculationService(TestData.CLOCK);
    private final OptionChainService service = new OptionChainService(null, calc, TestData.properties());

    @Test
    void groupsByExpirationAndMergesCallsAndPutsByStrike() {
        OptionDataset data = new OptionDataset(List.of(
                TestData.leg(OptionType.PUT, TestData.AUG_28, "640", "3", "3.1", -0.3, -0.1, 10L, 1, 0.2),
                TestData.call("680", "0.35", "0.40", 0.08, -0.018),
                TestData.put("625", "0.28", "0.32", -0.07, -0.016),
                TestData.call("640", "11.0", "11.2", 0.8, -0.1),
                TestData.put("640", "0.9", "1.0", -0.2, -0.05),
                TestData.leg(OptionType.CALL, TestData.AUG_21, "700", "0.05", "0.10", null, null, 10L, 1, null)),
                List.of(), 0, 0);

        OptionChain chain = service.buildChain("SPY", TestData.spy(), data, "indicative");

        assertThat(chain.expirations()).extracting(ExpirationGroup::expiration).containsExactly(TestData.AUG_21, TestData.AUG_28);
        ExpirationGroup aug21 = chain.expirations().get(0);
        assertThat(aug21.label()).isEqualTo("21 Aug 26");
        assertThat(chain.expirations().get(1).label()).isEqualTo("28 Aug 26 (W)");
        assertThat(aug21.dte()).isEqualTo(12);
        assertThat(aug21.rows()).extracting(r -> r.strike().toPlainString()).containsExactly("625", "640", "680", "700");

        StrikeRow row640 = aug21.rows().get(1);
        assertThat(row640.call()).isNotNull();
        assertThat(row640.put()).isNotNull();
        assertThat(row640.callInTheMoney()).isTrue();
        assertThat(row640.putInTheMoney()).isFalse();
        assertThat(aug21.spotRowIndex()).isEqualTo(2);   // divider between 640 and 680
        assertThat(aug21.callCount()).isEqualTo(3);
        assertThat(aug21.putCount()).isEqualTo(2);
        assertThat(chain.contractsMissingGreeks()).isEqualTo(1);
        assertThat(chain.warnings()).anyMatch(w -> w.contains("no Delta/Theta"));
    }

    @Test
    void monthlyIsTheThirdFriday() {
        assertThat(OptionChainService.isMonthly(LocalDate.of(2026, 8, 21))).isTrue();
        assertThat(OptionChainService.isMonthly(LocalDate.of(2026, 8, 14))).isFalse();
        assertThat(OptionChainService.isMonthly(LocalDate.of(2026, 9, 18))).isTrue();
        assertThat(OptionChainService.isMonthly(LocalDate.of(2026, 9, 17))).isFalse();
    }
}
