package com.premiumscanner.service;

import com.premiumscanner.client.dto.BarDto;
import com.premiumscanner.client.dto.GreeksDto;
import com.premiumscanner.client.dto.OptionSnapshotDto;
import com.premiumscanner.client.dto.QuoteDto;
import com.premiumscanner.client.dto.StockSnapshotDto;
import com.premiumscanner.client.dto.TradeDto;
import com.premiumscanner.config.AlpacaProperties;
import com.premiumscanner.domain.OptionContract;
import com.premiumscanner.domain.OptionSnapshot;
import com.premiumscanner.domain.UnderlyingQuote;
import com.premiumscanner.exception.UnderlyingPriceUnavailableException;
import com.premiumscanner.support.TestData;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AlpacaOptionServiceTest {

    private final AlpacaOptionService service = new AlpacaOptionService(null, new OptionCalculationService(TestData.CLOCK),
            new AlpacaProperties("k", "s", null, null, null, null, null, null, null, null, null, null, null, null, null),
            TestData.CLOCK);

    private final OptionContract contract = OccSymbolParser.parse("SPY260821C00680000", "SPY").orElseThrow();

    @Test
    void underlyingPricePrefersTheFresherOfTradeAndQuote() {
        StockSnapshotDto s = new StockSnapshotDto("SPY",
                new TradeDto(649.50, 100L, "2026-08-07T19:58:00Z"),
                new QuoteDto(649.98, 1L, 650.02, 1L, "2026-08-07T19:59:59Z"), null, null);
        UnderlyingQuote q = AlpacaOptionService.selectUnderlyingPrice("SPY", s);
        assertThat(q.price()).isEqualByComparingTo("650.00");
        assertThat(q.source()).isEqualTo("quote midpoint");
    }

    @Test
    void underlyingPriceFallsBackToBars() {
        StockSnapshotDto s = new StockSnapshotDto("SPY", null, new QuoteDto(0.0, 0L, 0.0, 0L, null),
                null, new BarDto(640.0, 652.0, 638.0, 648.25, 1L, "2026-08-06T04:00:00Z"));
        UnderlyingQuote q = AlpacaOptionService.selectUnderlyingPrice("SPY", s);
        assertThat(q.price()).isEqualByComparingTo("648.25");
        assertThat(q.source()).isEqualTo("previous close");
    }

    @Test
    void missingUnderlyingPriceIsAnError() {
        StockSnapshotDto s = new StockSnapshotDto("SPY", null, null, null, null);
        assertThatThrownBy(() -> AlpacaOptionService.selectUnderlyingPrice("SPY", s))
                .isInstanceOf(UnderlyingPriceUnavailableException.class);
    }

    @Test
    void mapsSnapshotAndMergesOpenInterest() {
        OptionSnapshotDto dto = new OptionSnapshotDto(
                new QuoteDto(0.35, 20L, 0.40, 25L, "2026-08-07T19:59:00.123456789Z"),
                new TradeDto(0.37, 1L, "2026-08-07T19:50:00Z"),
                new GreeksDto(0.08, 0.004, -0.018, 0.12, 0.01), 0.18,
                new BarDto(0.3, 0.4, 0.3, 0.37, 1234L, "2026-08-07T04:00:00Z"), null);

        OptionSnapshot o = service.toSnapshot(contract, dto, Map.of(contract.symbol(), 2500L));

        assertThat(o.quote().mid()).isEqualByComparingTo("0.375");
        assertThat(o.quote().spread()).isEqualByComparingTo("0.05");
        assertThat(o.greeks().delta()).isEqualTo(0.08);
        assertThat(o.greeks().impliedVolatility()).isEqualTo(0.18);
        assertThat(o.openInterest()).isEqualTo(2500L);
        assertThat(o.volume()).isEqualTo(1234L);
        assertThat(o.dte()).isEqualTo(12);
        assertThat(o.dataIssues()).isEmpty();
    }

    @Test
    void recordsDataIssuesInsteadOfFailing() {
        OptionSnapshotDto dto = new OptionSnapshotDto(new QuoteDto(-1.0, 0L, 0.10, 5L, "bad-timestamp"),
                null, null, Double.NaN, null, null);

        OptionSnapshot o = service.toSnapshot(contract, dto, Map.of());

        assertThat(o.quote().bid()).isNull();
        assertThat(o.greeks().hasDeltaAndTheta()).isFalse();
        assertThat(o.greeks().impliedVolatility()).isNull();
        assertThat(o.openInterest()).isNull();
        assertThat(o.volume()).isZero();
        assertThat(o.dataIssues()).contains("Negative bid ignored", "No bid", "Greeks unavailable", "IV unavailable");
    }
}
