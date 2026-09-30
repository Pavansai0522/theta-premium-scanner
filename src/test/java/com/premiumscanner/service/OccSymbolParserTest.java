package com.premiumscanner.service;

import com.premiumscanner.domain.OptionContract;
import com.premiumscanner.domain.OptionType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class OccSymbolParserTest {

    @Test
    void parsesCall() {
        OptionContract c = OccSymbolParser.parse("SPY260821C00680000", "SPY").orElseThrow();
        assertThat(c.type()).isEqualTo(OptionType.CALL);
        assertThat(c.strike()).isEqualByComparingTo("680");
        assertThat(c.strike().scale()).isGreaterThanOrEqualTo(0);
        assertThat(c.expiration()).isEqualTo(LocalDate.of(2026, 8, 21));
        assertThat(c.underlying()).isEqualTo("SPY");
    }

    @Test
    void parsesPutWithFractionalStrike() {
        OptionContract c = OccSymbolParser.parse("SPY260821P00622500", "SPY").orElseThrow();
        assertThat(c.type()).isEqualTo(OptionType.PUT);
        assertThat(c.strike()).isEqualByComparingTo(new BigDecimal("622.5"));
    }

    @Test
    void parsesAdjustedRoot() {
        OptionContract c = OccSymbolParser.parse("AAPL1260918C00200000", null).orElseThrow();
        assertThat(c.underlying()).isEqualTo("AAPL1");
        assertThat(c.strike()).isEqualByComparingTo("200");
    }

    @Test
    void rejectsGarbageAndImpossibleDates() {
        assertThat(OccSymbolParser.parse("NOT_AN_OPTION", "SPY")).isEmpty();
        assertThat(OccSymbolParser.parse("SPY261341C00680000", "SPY")).isEmpty();
        assertThat(OccSymbolParser.parse("SPY260821X00680000", "SPY")).isEmpty();
        assertThat(OccSymbolParser.parse("SPY260821C00000000", "SPY")).isEmpty();
        assertThat(OccSymbolParser.parse(null, "SPY")).isEmpty();
    }
}
