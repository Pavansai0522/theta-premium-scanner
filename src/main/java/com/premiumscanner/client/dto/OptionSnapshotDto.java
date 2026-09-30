package com.premiumscanner.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record OptionSnapshotDto(QuoteDto latestQuote, TradeDto latestTrade, GreeksDto greeks,
                                Double impliedVolatility, BarDto dailyBar, BarDto prevDailyBar) {
}
